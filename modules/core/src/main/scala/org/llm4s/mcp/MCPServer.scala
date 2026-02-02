package org.llm4s.mcp

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.toolapi.ToolFunction
import org.slf4j.LoggerFactory
import ujson.{ Obj, Str }
import upickle.default.{ read => upickleRead, write => upickleWrite }

import java.net.InetSocketAddress
import java.util.UUID
import scala.collection.mutable
import scala.util.{ Failure, Success, Try, Using }

/**
 * Configuration options for the MCPServer.
 *
 * @param port The port to bind to (e.g., 8080)
 * @param path The path for the MCP endpoint (e.g., "/mcp")
 * @param name The server name to report in initialization
 * @param version The server version to report in initialization
 */
case class MCPServerOptions(
  port: Int,
  path: String,
  name: String,
  version: String
)

/**
 * A generic, reusable Model Context Protocol (MCP) Server.
 *
 * This server hosts a list of llm4s `ToolFunction`s and exposes them
 * via the MCP protocol (Streamable HTTP 2025-06-18 and SSE 2024-11-05).
 *
 * Usage:
 * ```scala
 * val tools = Seq(myTool1, myTool2)
 * val options = MCPServerOptions(8080, "/mcp", "MyServer", "1.0")
 * val server = new MCPServer(options, tools)
 * server.start()
 * ```
 *
 * @param options Server configuration
 * @param tools List of tools to expose
 */
class MCPServer(
  options: MCPServerOptions,
  tools: Seq[ToolFunction[_, _]]
) {
  private val logger = LoggerFactory.getLogger(getClass)
  private var server: Option[HttpServer] = None
  
  // Map for fast tool lookup
  private val toolMap: Map[String, ToolFunction[_, _]] = tools.map(t => t.name -> t).toMap

  def start(): Unit = {
    if (server.isDefined) {
      logger.warn("MCPServer is already running")
      return
    }

    try {
      val httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", options.port), 0)
      httpServer.createContext(options.path, new MCPHandler)
      httpServer.setExecutor(null) // Default executor
      httpServer.start()
      server = Some(httpServer)
      val actualPort = httpServer.getAddress.getPort
      logger.info(s"MCPServer '${options.name}' started on http://127.0.0.1:$actualPort${options.path}")
      logger.info(s"Exposing ${tools.size} tools: ${tools.map(_.name).mkString(", ")}")
    } catch {
      case e: Exception =>
        logger.error(s"Failed to start MCPServer: ${e.getMessage}", e)
        throw e
    }
  }

  def stop(delay: Int = 0): Unit = {
    server.foreach { s =>
      logger.info("Stopping MCPServer...")
      s.stop(delay)
      server = None
    }
  }
  
  def boundPort: Int = server.map(_.getAddress.getPort).getOrElse(options.port)

  // Session management logic (internal)
  private case class Session(
    id: String,
    protocolVersion: String,
    created: Long = System.currentTimeMillis()
  )

  private object SessionStore {
    private val sessions = mutable.Map[String, Session]()

    def createSession(protocolVersion: String): Session = {
      val session = Session(UUID.randomUUID().toString, protocolVersion)
      sessions(session.id) = session
      logger.debug(s"Created session: ${session.id} for protocol $protocolVersion")
      session
    }

    def getSession(id: String): Option[Session] = sessions.get(id)

    def removeSession(id: String): Boolean = {
      val existed = sessions.remove(id).isDefined
      if (existed) logger.debug(s"Removed session: $id")
      existed
    }
  }

  // HTTP Handler implementation
  private class MCPHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      val method = exchange.getRequestMethod
      logger.debug(s"$method ${exchange.getRequestURI}")

      Try {
        method match {
          case "POST"   => handlePOST(exchange)
          case "GET"    => handleGET(exchange)
          case "DELETE" => handleDELETE(exchange)
          case _        => sendErrorResponse(exchange, 405, "Method not allowed")
        }
      }.recover { case e =>
        logger.error(s"Unhandled error in $method: ${e.getMessage}", e)
        sendErrorResponse(exchange, 500, s"Internal server error: ${e.getMessage}")
      }
    }

    private def handlePOST(exchange: HttpExchange): Unit = {
      val result = for {
        body    <- Try(scala.io.Source.fromInputStream(exchange.getRequestBody).mkString)
        request <- Try(upickleRead[JsonRpcRequest](body))
      } yield request

      result match {
        case Success(request) =>
          logger.debug(s"Request: ${request.method} (id: ${request.id})")

          // Protocol Version Check
          val protocolValid = if (request.method != "initialize") {
             Option(exchange.getRequestHeaders.getFirst("MCP-Protocol-Version")).forall { version =>
               version.startsWith("2024-") || version.startsWith("2025-")
             }
          } else true

          if (!protocolValid) {
             val version = exchange.getRequestHeaders.getFirst("MCP-Protocol-Version")
             sendJsonRpcError(exchange, request.id, MCPErrorCodes.INVALID_PROTOCOL_VERSION, s"Unsupported protocol version: $version")
          } else {
             val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))

             request.method match {
               case "initialize" => handleInitialize(exchange, request)
               case "tools/list" => handleWithSession(exchange, request, sessionId, handleToolsList)
               case "tools/call" => handleWithSession(exchange, request, sessionId, handleToolsCall)
               case _            => sendJsonRpcError(exchange, request.id, MCPErrorCodes.METHOD_NOT_FOUND, "Method not found")
             }
          }

        case Failure(e) =>
          logger.error(s"Failed to parse request: ${e.getMessage}")
          sendJsonRpcError(exchange, "unknown", MCPErrorCodes.PARSE_ERROR, "Parse error")
      }
    }

    private def handleInitialize(exchange: HttpExchange, request: JsonRpcRequest): Unit = {
      val initRequest = request.params
        .flatMap(params => Try(upickleRead[InitializeRequest](params.toString)).toOption)
        .getOrElse(InitializeRequest("2024-11-05", MCPCapabilities(), ClientInfo("unknown", "1.0")))

      // Protocol negotiation
      val clientVersion = initRequest.protocolVersion
      val protocolVersion = clientVersion match {
        case v if v.startsWith("2025-06-18") => "2025-06-18"
        case v if v.startsWith("2025-03-26") => "2025-03-26"
        case _                               => "2024-11-05"
      }

      logger.info(s"Initializing with protocol: $protocolVersion")

      // Create session for modern protocols
      val sessionOpt = if (protocolVersion == "2025-06-18" || protocolVersion == "2025-03-26") {
        Some(SessionStore.createSession(protocolVersion))
      } else None

      val response = JsonRpcResponse(
        id = request.id,
        result = Some(upickle.default.writeJs(
          InitializeResponse(
            protocolVersion = protocolVersion,
            capabilities = MCPCapabilities(tools = Some(Obj())),
            serverInfo = ServerInfo(options.name, options.version)
          )
        ))
      )

      sendJsonRpcResponse(exchange, response, sessionOpt.map(_.id))
    }

    private def handleWithSession(
      exchange: HttpExchange,
      request: JsonRpcRequest,
      sessionId: Option[String],
      handler: JsonRpcRequest => JsonRpcResponse
    ): Unit = {
      // Basic session validation
      sessionId.foreach { id =>
        if (SessionStore.getSession(id).isEmpty) {
           logger.warn(s"Unknown session: $id")
        }
      }
      val response = handler(request)
      sendJsonRpcResponse(exchange, response, sessionId)
    }

    private def handleToolsList(request: JsonRpcRequest): JsonRpcResponse = {
      // Convert internal ToolFunctions to MCPTools
      val mcpTools = tools.map { tool =>
        MCPTool(
          name = tool.name,
          description = tool.description,
          inputSchema = tool.toOpenAITool(strict = false)("function")("parameters")
        )
      }
      
      JsonRpcResponse(
         id = request.id,
         result = Some(upickle.default.writeJs(ToolsListResponse(mcpTools)))
      )
    }

    private def handleToolsCall(request: JsonRpcRequest): JsonRpcResponse = {
      val toolName = request.params.flatMap(_.obj.get("name")).map(_.str).getOrElse("")
      val arguments = request.params.flatMap(_.obj.get("arguments")).getOrElse(ujson.Obj())

      logger.info(s"Executing tool: $toolName")

      toolMap.get(toolName) match {
        case Some(tool) =>
          // Execute with arguments
          tool.execute(arguments) match {
            case Right(resultJson) =>
              // Convert result to string/text for MCPContent
              // This naive stringification works for simple values; 
              // for objects it renders the JSON string.
              val resultString = resultJson match {
                 case ujson.Str(s) => s
                 case other => other.render()
              }
              
              val response = ToolsCallResponse(
                content = Seq(MCPContent(`type` = "text", text = Some(resultString))),
                isError = Some(false)
              )
              JsonRpcResponse(id = request.id, result = Some(upickle.default.writeJs(response)))

            case Left(error) =>
              // execution failed
               logger.error(s"Tool execution failed: $toolName - $error")
               // We return a "successful" JSON-RPC response but with isError=true in content 
               // OR a JSON-RPC error. MCP spec allows either, but isError in content is often preferred for application errors.
               // Let's use JSON-RPC error for consistency with demo.
               JsonRpcResponse(
                 id = request.id, 
                 error = Some(JsonRpcError(MCPErrorCodes.TOOL_EXECUTION_ERROR, s"Tool failed: ${error}", None))
               )
          }

        case None =>
          JsonRpcResponse(
            id = request.id,
            error = Some(JsonRpcError(MCPErrorCodes.TOOL_NOT_FOUND, s"Tool not found: $toolName", None))
          )
      }
    }

    private def handleGET(exchange: HttpExchange): Unit = {
       val acceptHeader = Option(exchange.getRequestHeaders.getFirst("Accept")).getOrElse("")
       if (!acceptHeader.contains("text/event-stream")) {
         sendErrorResponse(exchange, 406, "GET requires Accept: text/event-stream")
         return
       }
       val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))
       sessionId match {
         case Some(id) if SessionStore.getSession(id).isDefined =>
           sendSSEStream(exchange, id)
         case Some(id) =>
           sendErrorResponse(exchange, 400, s"Invalid session: $id")
         case None =>
           sendErrorResponse(exchange, 400, "Missing mcp-session-id header for SSE")
       }
    }

    private def handleDELETE(exchange: HttpExchange): Unit = {
      val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))
      sessionId match {
        case Some(id) =>
          if (SessionStore.removeSession(id)) {
            sendResponse(exchange, 200, "application/json", """{"status":"session_terminated"}""")
          } else {
            sendErrorResponse(exchange, 404, "Session not found")
          }
        case None =>
           sendErrorResponse(exchange, 400, "Missing mcp-session-id header")
      }
    }

    // --- Helper methods ---

    private def sendJsonRpcError(exchange: HttpExchange, id: String, code: Int, message: String): Unit = {
      val error = JsonRpcResponse(id = id, error = Some(JsonRpcError(code, message, None)))
      sendJsonRpcResponse(exchange, error)
    }

    private def sendJsonRpcResponse(exchange: HttpExchange, response: JsonRpcResponse, sessionId: Option[String] = None): Unit = {
      val json = upickleWrite(response)
      sessionId.foreach(id => exchange.getResponseHeaders.set("mcp-session-id", id))
      sendResponse(exchange, 200, "application/json", json)
    }

    private def sendResponse(exchange: HttpExchange, statusCode: Int, contentType: String, body: String): Unit = {
       val bytes = body.getBytes("UTF-8")
       exchange.getResponseHeaders.set("Content-Type", contentType)
       exchange.getResponseHeaders.set("Content-Length", bytes.length.toString)
       exchange.sendResponseHeaders(statusCode, bytes.length.toLong)
       
       Using(exchange.getResponseBody) { os =>
         os.write(bytes)
         os.flush()
       }.get
    }

    private def sendErrorResponse(exchange: HttpExchange, code: Int, message: String): Unit =
      sendResponse(exchange, code, "text/plain", message)

    private def sendSSEStream(exchange: HttpExchange, sessionId: String): Unit = {
       exchange.getResponseHeaders.set("Content-Type", "text/event-stream")
       exchange.getResponseHeaders.set("Cache-Control", "no-cache")
       exchange.getResponseHeaders.set("Connection", "keep-alive")
       exchange.getResponseHeaders.set("mcp-session-id", sessionId)

       val sseData = ": SSE stream opened\n\n" +
          "data: {\"jsonrpc\":\"2.0\",\"method\":\"notification/stream_started\",\"params\":{\"session\":\"" + sessionId + "\"}}\n\n"
       
       sendResponse(exchange, 200, "text/event-stream", sseData)
    }
  }
}
