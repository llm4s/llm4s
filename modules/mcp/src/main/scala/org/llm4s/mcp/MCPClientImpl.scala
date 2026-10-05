package org.llm4s.mcp

import cats.implicits._
import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.toolapi._
import org.llm4s.types.Result
import org.slf4j.LoggerFactory
import ujson.{ Value, read => ujsonRead }

import java.util.concurrent.atomic.AtomicLong
import scala.util.{ Failure, Success, Try }

/**
 * `MCPClient` implementation that connects to an MCP server over JSON-RPC.
 *
 * == Transport auto-detection ==
 * For HTTP-based server configs (`StreamableHTTPTransport` or `SSETransport`),
 * this client first attempts to connect using the MCP 2025-06-18 Streamable HTTP
 * protocol.  If the server responds with HTTP 404 or 405, it automatically falls
 * back to the older 2024-11-05 HTTP+SSE protocol.  Stdio servers always use the
 * 2024-11-05 protocol.
 *
 * == Error swallowing in `getTools` ==
 * `getTools()` returns a `Left` only for a cancellation: any other failure during tool discovery
 * (network error, parse error, missing transport) is logged and an empty sequence is
 * returned instead. Callers cannot distinguish "server has no tools" from
 * "server could not be reached". An interrupted call is not a failure to hide: it returns
 * `Left(CancelledError)` with the thread's interrupt flag still set (design section 4.4).
 *
 * == Thread safety ==
 * This class is not thread-safe. Concurrent calls to `initialize`, `getTools`,
 * or `close` from different threads require external synchronisation.
 *
 * @param config Server configuration including transport type, URL/command, and timeout.
 */
class MCPClientImpl(config: MCPServerConfig) extends MCPClient {
  private val logger                                      = LoggerFactory.getLogger(getClass)
  private[mcp] var transport: Option[MCPTransportImpl]    = None
  private val requestId                                   = new AtomicLong(0)
  private var initialized                                 = false
  @volatile private var toolHints: Map[String, ToolHints] = Map.empty
  private var protocolVersion                             = "2025-06-18" // Updated to latest version

  logger.info(s"MCPClientImpl created for server: ${config.name}")

  // Initialize transport with backward compatibility detection
  private def initializeTransport(): Result[MCPTransportImpl] =
    transport match {
      case Some(t) => Right(t)
      case None =>
        config.transport match {
          case StreamableHTTPTransport(url, name) =>
            tryHttpTransportWithFallback(url, name)
          case SSETransport(url, name) =>
            tryHttpTransportWithFallback(url, name)
          case StdioTransport(command, name) =>
            // Stdio transport uses 2024-11-05 protocol version
            val stdioTransport = new StdioTransportImpl(command, name)
            transport = Some(stdioTransport)
            protocolVersion = "2024-11-05"
            Right(stdioTransport)
        }
    }

  // Unified HTTP transport logic: try Streamable HTTP first, fallback to SSE
  private def tryHttpTransportWithFallback(url: String, name: String): Result[MCPTransportImpl] = {
    // Try new 2025-06-18 Streamable HTTP transport first
    logger.info(s"Attempting to connect using Streamable HTTP transport (2025-06-18) to $url")
    val newTransport = new StreamableHTTPTransportImpl(url, name, config.timeout)

    // Test with a simple capability check (not full initialization)
    val capabilityRequest = createCapabilityCheckRequest("2025-06-18")
    newTransport.sendRequest(capabilityRequest) match {
      case Right(_) =>
        logger.info(s"Successfully connected using Streamable HTTP transport (2025-06-18)")
        transport = Some(newTransport)
        protocolVersion = "2025-06-18"
        isTransportInitialized = true // Mark as initialized during testing
        Right(newTransport)
      case Left(error)
          if error.message.contains("405") || error.message
            .contains("404") || error.message.contains("Method Not Allowed") =>
        // Server doesn't support new transport, try fallback
        logger.info(s"Server doesn't support Streamable HTTP, attempting fallback to HTTP+SSE (2024-11-05)")
        newTransport.close()

        // Try old transport
        val oldTransport         = new SSETransportImpl(url, name, config.timeout)
        val oldCapabilityRequest = createCapabilityCheckRequest("2024-11-05")
        oldTransport.sendRequest(oldCapabilityRequest) match {
          case Right(_) =>
            logger.info(s"Successfully connected using HTTP+SSE transport (2024-11-05)")
            transport = Some(oldTransport)
            protocolVersion = "2024-11-05"
            isTransportInitialized = true // Mark as initialized during testing
            Right(oldTransport)
          case Left(cancelled: CancelledError) =>
            oldTransport.close()
            Left(cancelled)
          case Left(fallbackError) =>
            logger.error(s"Both transport methods failed. New: ${error.message}, Old: ${fallbackError.message}")
            oldTransport.close()
            Left(SimpleError(s"Failed to connect with both transports. Latest error: ${fallbackError.message}"))
        }
      case Left(error) =>
        logger.error(s"Failed to connect using Streamable HTTP transport: ${error.message}")
        newTransport.close()
        Left(error)
    }
  }

  // Create a simple capability check request (use initialize but mark as test)
  private def createCapabilityCheckRequest(version: String): JsonRpcRequest =
    createInitializeRequest(version)

  private def createInitializeRequest(version: String): JsonRpcRequest =
    JsonRpcRequest(
      jsonrpc = "2.0", // Explicitly set to ensure serialization
      id = generateId(),
      method = "initialize",
      params = Some(
        ujson.Obj(
          "protocolVersion" -> ujson.Str(version),
          "capabilities" -> ujson.Obj(
            "tools"    -> ujson.Obj(),
            "roots"    -> ujson.Obj("listChanged" -> ujson.Bool(false)),
            "sampling" -> ujson.Obj()
          ),
          "clientInfo" -> ujson.Obj(
            "name"    -> ujson.Str("llm4s-mcp"),
            "version" -> ujson.Str("1.0.0")
          )
        )
      )
    )

  // Performs MCP protocol handshake with the server
  override def initialize(): Result[Unit] =
    if (initialized) {
      Right(())
    } else {
      initializeTransport().flatMap { transportImpl =>
        // Check if we already did initialization during transport testing
        if (isTransportInitialized) {
          // Send initialized notification to complete handshake (notifications have no ID)
          val initializedNotification = JsonRpcNotification(
            jsonrpc = "2.0",
            method = "notifications/initialized",
            params = Some(ujson.Obj())
          )

          transportImpl.sendNotification(initializedNotification) match {
            case Right(_) =>
              initialized = true
              logger.info(s"Completed MCP client initialization for ${config.name} with existing connection")
              Right(())
            case Left(cancelled: CancelledError) => Left(cancelled)
            case Left(notificationError) =>
              logger.warn(
                s"Failed to send initialized notification: ${notificationError.message}, but continuing anyway"
              )
              // Some servers might not require the initialized notification
              initialized = true
              Right(())
          }
        } else {
          // Full initialization process
          val initRequest = createInitializeRequest(protocolVersion)

          transportImpl.sendRequest(initRequest) match {
            case Right(response) =>
              response.result match {
                case Some(result) =>
                  Try {
                    val serverProtocolVersion = result("protocolVersion").str
                    logger.info(s"Server supports protocol version: $serverProtocolVersion")

                    // Validate protocol version compatibility
                    if (serverProtocolVersion.startsWith("2024-") || serverProtocolVersion.startsWith("2025-")) {
                      // Send initialized notification to complete the handshake (notifications have no ID)
                      val initializedNotification = JsonRpcNotification(
                        jsonrpc = "2.0",
                        method = "notifications/initialized",
                        params = Some(ujson.Obj())
                      )

                      transportImpl.sendNotification(initializedNotification) match {
                        case Right(_) =>
                          initialized = true
                          logger.info(
                            s"Successfully initialized MCP client for ${config.name} with protocol $serverProtocolVersion"
                          )
                          Right(())
                        case Left(cancelled: CancelledError) => Left(cancelled)
                        case Left(notificationError) =>
                          logger.warn(
                            s"Failed to send initialized notification: ${notificationError.message}, but continuing anyway"
                          )
                          // Some servers might not require the initialized notification
                          initialized = true
                          Right(())
                      }
                    } else {
                      Left(SimpleError(s"Unsupported protocol version: $serverProtocolVersion"))
                    }
                  }.getOrElse(Left(SimpleError("Invalid initialization response format")))
                case None =>
                  Left(SimpleError("Initialize request failed: no result in response"))
              }
            case Left(cancelled: CancelledError) => Left(cancelled)
            case Left(error) =>
              Left(SimpleError(s"Initialize request failed: ${error.message}"))
          }
        }
      }
    }

  // Track if transport was initialized during testing
  private var isTransportInitialized = false

  /**
   * Retrieves all tools advertised by the MCP server, converting them to
   * `ToolFunction` instances that the agent framework can invoke.
   *
   * Calls `initialize()` automatically if not already connected.  Any error
   * during transport initialisation, tool listing, or JSON parsing is logged
   * and swallowed: this method always returns `Right(tools)` where `tools` is
   * the (possibly empty) sequence of successfully parsed tool definitions.
   *
   * @return always `Right`; `Right(Seq.empty)` on any communication or parse failure
   */
  override def getTools(): Result[Seq[ToolFunction[_, _]]] = {
    val result = for {
      _             <- initialize() // Ensure we're initialized
      transportImpl <- transport.toRight(SimpleError(s"No transport available for ${config.name}"): LLMError)
      tools         <- trySendingRequest(transportImpl)
    } yield tools
    result match {
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error) =>
        logger.error(error.message)
        Right(Seq.empty)
      case ok => ok
    }
  }

  def trySendingRequest(transportImpl: MCPTransportImpl): Result[Seq[ToolFunction[_, _]]] = {
    val result = for {
      request    <- MCPClientImpl.listRequest.copy(id = generateId()).asRight[LLMError]
      response   <- transportImpl.sendRequest(request)
      toolsValue <- response.result.toRight(SimpleError(s"No tools result from ${config.name}"): LLMError)
      tools      <- parseTools(toolsValue)
    } yield tools

    result match {
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error) =>
        logger.warn(error.message)
        Right(Seq.empty)
      case ok => ok
    }
  }

  private def parseTools(value: Value): Result[Seq[ToolFunction[_, _]]] = {
    val result = Try {
      val toolsData = value("tools").arr
      (toolsData.map(convertMCPToolToToolFunction).toSeq, toolsData.map(MCPClientImpl.hintsOf).toMap)
    }
    result.fold(
      ex => logger.error("Failed to parse tools from {}: {}", config.name, ex.getMessage),
      { case (tools, hints) =>
        toolHints = hints
        logger.info("Successfully retrieved from {} {} tools", config.name, tools.size)
      }
    )
    result.map(_._1).getOrElse(Seq.empty).asRight[LLMError]
  }

  override def getToolHints(): Map[String, ToolHints] = toolHints

  // Closes the transport connection and resets initialization state
  override def close(): Unit = {
    transport.foreach(_.close())
    transport = None
    initialized = false
  }

  // Generates unique request IDs for JSON-RPC protocol
  private def generateId(): String = requestId.incrementAndGet().toString

  // Converts an MCP tool definition to llm4s ToolFunction format
  private def convertMCPToolToToolFunction(toolJson: Value): ToolFunction[Value, Value] = {
    val name        = toolJson("name").str
    val description = toolJson("description").str
    val inputSchema = toolJson("inputSchema")

    // Convert MCP input schema to our ObjectSchema format
    val schema = convertMCPSchemaToObjectSchema(name, inputSchema)

    new ToolFunction[Value, Value](
      name = name,
      description = description,
      schema = schema,
      handler = createMCPToolHandler(name)
    )
  }

  // Converts MCP JSON Schema to ObjectSchema format
  private def convertMCPSchemaToObjectSchema(toolName: String, mcpSchema: Value): ObjectSchema[Value] = {
    // MCP uses JSON Schema format for inputSchema
    val properties = mcpSchema.obj.get("properties") match {
      case Some(props) =>
        props.obj.map { case (propName, propSchema) =>
          PropertyDefinition(
            name = propName,
            schema = convertJsonSchemaToSchemaDefinition(propSchema),
            required = mcpSchema.obj
              .get("required")
              .flatMap(_.arrOpt)
              .exists(_.value.exists(_.strOpt.contains(propName)))
          )
        }.toSeq
      case None => Seq.empty
    }

    ObjectSchema[Value](
      description = s"Parameters for $toolName",
      properties = properties,
      additionalProperties = false
    )
  }

  // Converts individual JSON Schema property to llm4s SchemaDefinition
  private def convertJsonSchemaToSchemaDefinition(jsonSchema: Value): SchemaDefinition[_] = {
    val schemaType  = jsonSchema.obj.get("type").flatMap(_.strOpt).getOrElse("string")
    val description = jsonSchema.obj.get("description").flatMap(_.strOpt).getOrElse("Parameter")

    schemaType match {
      case "string" =>
        val enumValues = jsonSchema.obj
          .get("enum")
          .flatMap(_.arrOpt)
          .map(_.value.flatMap(_.strOpt).toSeq)
        StringSchema(description, enumValues)

      case "number" =>
        NumberSchema(description)

      case "integer" =>
        IntegerSchema(description)

      case "boolean" =>
        BooleanSchema(description)

      case "array" =>
        val itemSchema = jsonSchema.obj
          .get("items")
          .map(convertJsonSchemaToSchemaDefinition)
          .getOrElse(StringSchema("Array item"))
        ArraySchema("Array parameter", itemSchema)

      case _ =>
        StringSchema(description)
    }
  }

  // Creates tool execution handler that delegates to MCP server
  private def createMCPToolHandler(toolName: String): SafeParameterExtractor => Either[String, Value] = { params =>
    transport match {
      case Some(transportImpl) =>
        val callRequest = JsonRpcRequest(
          jsonrpc = "2.0",
          id = generateId(),
          method = "tools/call", // method value for executing a specific tool
          params = Some(
            ujson.Obj(
              "name"      -> ujson.Str(toolName),
              "arguments" -> params.params
            )
          )
        )

        transportImpl.sendRequest(callRequest) match {
          case Right(response) =>
            response.result match {
              case Some(result) =>
                // Per the MCP spec a tool-level failure is a normal result flagged `isError: true`
                val isToolError = result.objOpt.flatMap(_.get("isError")).flatMap(_.boolOpt).contains(true)
                Try {
                  // MCP returns content array with text results
                  val content = result("content").arr
                  if (content.nonEmpty) {
                    val firstContent = content(0)
                    val text         = firstContent("text").str

                    // Try to parse as JSON, fallback to string result
                    if (isToolError) ujson.Str(text) else Try(ujsonRead(text)).getOrElse(ujson.Str(text))
                  } else if (isToolError) {
                    ujson.Str("server reported an error")
                  } else {
                    ujson.Obj("result" -> ujson.Str("No content returned"))
                  }
                } match {
                  case Success(parsed) if isToolError =>
                    Left(s"Tool call failed: ${parsed.str}")
                  case Success(parsed) => Right(parsed)
                  case Failure(_) if isToolError =>
                    Left("Tool call failed: server reported an error")
                  case Failure(e) => Left(s"Failed to parse tool result: ${e.getMessage}")
                }
              case None =>
                Left("Tool call failed: no result")
            }
          // The flag a cancelled call leaves set is how ToolRegistry knows to report it cancelled
          case Left(error) =>
            Left(s"Tool call failed: ${error.message}")
        }
      case None =>
        Left("No transport available")
    }
  }
}

object MCPClientImpl {

  /** A tool's name and the hints its MCP annotations declare (the specification's defaults when it has none). */
  private[mcp] def hintsOf(toolJson: Value): (String, ToolHints) =
    toolJson("name").str -> MCPToolAnnotations
      .fromJson(toolJson.objOpt.flatMap(_.get("annotations")).getOrElse(ujson.Null))
      .toToolHints

  val listRequest: JsonRpcRequest = JsonRpcRequest(
    jsonrpc = "2.0",
    id = "",
    method = "tools/list", // method value for getting available tools
    params = None          // Optional params omitted per JSON-RPC 2.0 spec
  )
}
