package org.llm4s.mcp

import org.llm4s.toolapi._
import org.slf4j.LoggerFactory
import ujson.{ Value, read => ujsonRead }

import java.util.concurrent.atomic.AtomicLong
import scala.util.{ Failure, Success, Try }

/**
 * Implementation of MCP client that connects to and communicates with MCP servers.
 * Handles JSON-RPC communication, tool discovery, and execution delegation.
 */
class MCPClientImpl(config: MCPServerConfig) extends MCPClient {
  private val logger      = LoggerFactory.getLogger(getClass)
  private val transport   = MCPTransport.create(config)
  private val requestId   = new AtomicLong(0)
  private var initialized = false

  logger.info(s"MCPClientImpl created for server: ${config.name}")

  // Performs MCP protocol handshake with the server
  override def initialize(): Either[String, Unit] = {
    if (initialized) return Right(())

    val initRequest = JsonRpcRequest(
      id = generateId(),
      method = "initialize", // method value for handshake/setup
      params = Some(
        ujson.Obj(
          "protocolVersion" -> ujson.Str("2024-11-05"), // consider switching to 2025-03-26 (without sse)
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

    transport.sendRequest(initRequest) match {
      case Right(response) =>
        response.result match {
          case Some(result) =>
            Try {
              val protocolVersion = result("protocolVersion").str
              // Validate protocol version compatibility
              if (protocolVersion.startsWith("2024-") || protocolVersion.startsWith("2025-")) {
                initialized = true
                Right(())
              } else {
                Left(s"Unsupported protocol version: $protocolVersion")
              }
            }.getOrElse(Left("Invalid initialization response format"))
          case None =>
            Left("Initialize request failed: no result in response")
        }
      case Left(errorMsg) =>
        Left(s"Initialize request failed: $errorMsg")
    }
  }

  // Retrieves and converts all available tools from the MCP server
  override def getTools(): Seq[ToolFunction[_, _]] = {
    // Ensure we're initialized
    initialize() match {
      case Left(errorMsg) =>
        logger.error(s"Failed to initialize MCP client for ${config.name}: $errorMsg")
        return Seq.empty
      case Right(_) => // Continue
    }

    val listRequest = JsonRpcRequest(
      id = generateId(),
      method = "tools/list", // method value for getting available tools
      params = None
    )

    transport.sendRequest(listRequest) match {
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
  }

  // Closes the transport connection and resets initialization state
  override def close(): Unit = {
    transport.close()
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

    transport.sendRequest(callRequest) match {
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
  }
}
