package org.llm4s.mcp

import org.llm4s.toolapi._
import org.slf4j.LoggerFactory
import ujson.{ Value, read => ujsonRead }

import java.util.concurrent.atomic.AtomicLong
import scala.util.{ Failure, Success, Try }

/**
 * Implementation of MCP client that connects to and communicates with MCP servers.
 * Handles JSON-RPC communication, tool discovery, and execution delegation.
 * Supports both 2025-06-18 (Streamable HTTP) and 2024-11-05 (HTTP+SSE) transports.
 */
class MCPClientImpl(config: MCPServerConfig) extends MCPClient {
  private val logger                              = LoggerFactory.getLogger(getClass)
  private var transport: Option[MCPTransportImpl] = None
  private val requestId                           = new AtomicLong(0)
  private var initialized                         = false
  private var protocolVersion                     = "2025-06-18" // Updated to latest version

  logger.info(s"MCPClientImpl created for server: ${config.name}")

  // Initialize transport with backward compatibility detection
  private def initializeTransport(): Either[String, MCPTransportImpl] =
    transport match {
      case Some(t) => Right(t)
      case None =>
        config.transport match {
          case StreamableHTTPTransport(url, name) =>
            tryHttpTransportWithFallback(url, name)
          case SSETransport(url, name) =>
            tryHttpTransportWithFallback(url, name)
          case StdioTransport(command, name) =>
            // Stdio transport remains unchanged
            val stdioTransport = new StdioTransportImpl(command, name)
            transport = Some(stdioTransport)
            Right(stdioTransport)
        }
    }

  // Unified HTTP transport logic: try Streamable HTTP first, fallback to SSE
  private def tryHttpTransportWithFallback(url: String, name: String): Either[String, MCPTransportImpl] = {
    // Try new 2025-06-18 Streamable HTTP transport first
    logger.info(s"Attempting to connect using Streamable HTTP transport (2025-06-18) to $url")
    val newTransport = new StreamableHTTPTransportImpl(url, name)

    // Test with a simple initialize request
    val testRequest = createInitializeRequest("2025-06-18")
    newTransport.sendRequest(testRequest) match {
      case Right(_) =>
        logger.info(s"Successfully connected using Streamable HTTP transport (2025-06-18)")
        transport = Some(newTransport)
        protocolVersion = "2025-06-18"
        Right(newTransport)
      case Left(error) if error.contains("405") || error.contains("404") =>
        // Server doesn't support new transport, try fallback
        logger.info(s"Server doesn't support Streamable HTTP, attempting fallback to HTTP+SSE (2024-11-05)")
        newTransport.close()

        // Try old transport
        val oldTransport   = new SSETransportImpl(url, name)
        val oldTestRequest = createInitializeRequest("2024-11-05")
        oldTransport.sendRequest(oldTestRequest) match {
          case Right(_) =>
            logger.info(s"Successfully connected using HTTP+SSE transport (2024-11-05)")
            transport = Some(oldTransport)
            protocolVersion = "2024-11-05"
            Right(oldTransport)
          case Left(fallbackError) =>
            logger.error(s"Both transport methods failed. New: $error, Old: $fallbackError")
            oldTransport.close()
            Left(s"Failed to connect with both transports. Latest error: $fallbackError")
        }
      case Left(error) =>
        logger.error(s"Failed to connect using Streamable HTTP transport: $error")
        newTransport.close()
        Left(error)
    }
  }

  private def createInitializeRequest(version: String): JsonRpcRequest =
    JsonRpcRequest(
      id = generateId(),
      method = "initialize",
      params = Some(
        ujson.Obj(
          "protocolVersion" -> ujson.Str(version),
          "capabilities" -> ujson.Obj(
            "tools" -> ujson.Obj()
          ),
          "clientInfo" -> ujson.Obj(
            "name"    -> ujson.Str("llm4s-mcp"),
            "version" -> ujson.Str("1.0.0")
          )
        )
      )
    )

  // Performs MCP protocol handshake with the server
  override def initialize(): Either[String, Unit] =
    if (initialized) {
      Right(())
    } else {
      initializeTransport().flatMap { transportImpl =>
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
                    initialized = true
                    logger.info(
                      s"Successfully initialized MCP client for ${config.name} with protocol $serverProtocolVersion"
                    )
                    Right(())
                  } else {
                    Left(s"Unsupported protocol version: $serverProtocolVersion")
                  }
                }.getOrElse(Left("Invalid initialization response format"))
              case None =>
                Left("Initialize request failed: no result in response")
            }
          case Left(errorMsg) =>
            Left(s"Initialize request failed: $errorMsg")
        }
      }
    }

  // Retrieves and converts all available tools from the MCP server
  override def getTools(): Seq[ToolFunction[_, _]] =
    // Ensure we're initialized
    initialize() match {
      case Left(errorMsg) =>
        logger.error(s"Failed to initialize MCP client for ${config.name}: $errorMsg")
        Seq.empty
      case Right(_) =>
        transport match {
          case Some(transportImpl) =>
            val listRequest = JsonRpcRequest(
              id = generateId(),
              method = "tools/list", // method value for getting available tools
              params = None
            )

            transportImpl.sendRequest(listRequest) match {
              case Right(response) =>
                response.result match {
                  case Some(result) =>
                    Try {
                      val toolsData = result("tools").arr
                      toolsData.map(convertMCPToolToToolFunction).toSeq
                    } match {
                      case Success(tools) =>
                        logger.info(s"Successfully retrieved ${tools.size} tools from ${config.name}")
                        tools
                      case Failure(exception) =>
                        logger.error(s"Failed to parse tools from ${config.name}: ${exception.getMessage}", exception)
                        Seq.empty
                    }
                  case None =>
                    logger.warn(s"No tools result from ${config.name}")
                    Seq.empty
                }
              case Left(errorMsg) =>
                logger.error(s"Failed to fetch tools from ${config.name}: $errorMsg")
                Seq.empty
            }
          case None =>
            logger.error(s"No transport available for ${config.name}")
            Seq.empty
        }
    }

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
                Try {
                  // MCP returns content array with text results
                  val content = result("content").arr
                  if (content.nonEmpty) {
                    val firstContent = content(0)
                    val text         = firstContent("text").str

                    // Try to parse as JSON, fallback to string result
                    Try(ujsonRead(text)).getOrElse(ujson.Str(text))
                  } else {
                    ujson.Obj("result" -> ujson.Str("No content returned"))
                  }
                } match {
                  case Success(parsed) => Right(parsed)
                  case Failure(e)      => Left(s"Failed to parse tool result: ${e.getMessage}")
                }
              case None =>
                Left("Tool call failed: no result")
            }
          case Left(errorMsg) =>
            Left(s"Tool call failed: $errorMsg")
        }
      case None =>
        Left("No transport available")
    }
  }
}
