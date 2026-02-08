package org.llm4s.mcp

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.toolapi.ToolFunction
import org.slf4j.LoggerFactory
import ujson.Obj
import upickle.default.{ read => upickleRead, write => upickleWrite }

import java.net.InetSocketAddress
import java.util.UUID

import scala.collection.concurrent.TrieMap
import java.util.concurrent.{ Executors, ExecutorService, TimeUnit }
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
 * via the MCP protocol (HTTP Transport 2025-06-18 only).
 *
 * NOTE: This server is intended for LOCAL DEVELOPMENT use only.
 * It does not implement authentication or strict security sandboxing.
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
  private val logger                           = LoggerFactory.getLogger(getClass)
  private var server: Option[HttpServer]       = None
  private var executorService: ExecutorService = _

  // Map for fast tool lookup
  private val toolMap: Map[String, ToolFunction[_, _]] = tools.map(t => t.name -> t).toMap

  def boundPort: Int = server.map(_.getAddress.getPort).getOrElse(-1)
  def getPort: Int   = boundPort

  def start(): Either[Exception, Unit] = synchronized {
    if (server.isDefined) {
      logger.warn("MCPServer is already running")
      return Right(())
    }

    logger.warn("!!! SECURITY WARNING !!!")
    logger.warn("This server is intended for local development only. Do not use in production.")
    logger.warn("It does not implement authentication or strict security boundaries.")

    Try {
      val httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", options.port), 0)
      httpServer.createContext(options.path, new MCPHandler)
      // Use a bounded thread pool to prevent resource exhaustion
      executorService = Executors.newFixedThreadPool(16)
      httpServer.setExecutor(executorService)
      httpServer.start()
      server = Some(httpServer)
      val actualPort = httpServer.getAddress.getPort
      logger.info(s"MCPServer '${options.name}' started on http://127.0.0.1:$actualPort${options.path}")
      logger.info(s"Exposing ${tools.size} tools: ${tools.map(_.name).mkString(", ")}")
    }.toEither.left.map { case e: Exception =>
      logger.error(s"Failed to start MCPServer: ${e.getMessage}", e)
      e
    }
  }

  def stop(delay: Int = 0): Unit = synchronized {
    server.foreach { s =>
      logger.info("Stopping MCPServer...")
      s.stop(delay)
      if (executorService != null) {
        executorService.shutdown()
        Try {
          if (!executorService.awaitTermination(delay.toLong, TimeUnit.SECONDS)) {
            executorService.shutdownNow()
          }
        }.recover { case _: InterruptedException => executorService.shutdownNow() }
        executorService = null
      }
      server = None
    }
  }

  // Session management logic (internal)
  private case class Session(
    id: String,
    protocolVersion: String,
    created: Long = System.currentTimeMillis()
  )

  private object SessionStore {
    private val sessions = TrieMap[String, Session]()

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
          case "DELETE" => handleDELETE(exchange)
          case _        => sendErrorResponse(exchange, 405, "Method not allowed")
        }
      }.recover { case e =>
        logger.error(s"Unhandled error in $method: ${e.getMessage}", e)
        sendErrorResponse(exchange, 500, s"Internal server error: ${e.getMessage}")
      }
    }

    private val MaxPayloadSize = 10 * 1024 * 1024 // 10 MB

    private def handlePOST(exchange: HttpExchange): Unit = {
      val contentLengthStr = Option(exchange.getRequestHeaders.getFirst("Content-Length"))
      val exceedsLimit     = contentLengthStr.flatMap(s => Try(s.toLong).toOption).exists(_ > MaxPayloadSize)

      if (exceedsLimit) {
        sendErrorResponse(exchange, 413, "Payload too large")
        return
      }

      val result = for {
        bodyStr <- readBodyWithLimit(exchange, MaxPayloadSize)
        json    <- Try(ujson.read(bodyStr))
      } yield (bodyStr, json)

      result match {
        case Success((bodyStr, json)) =>
          if (json.obj.contains("id")) {
            // It's a request
            Try(upickleRead[JsonRpcRequest](bodyStr)) match {
              case Success(request) =>
                logger.debug(s"Request: ${request.method} (id: ${request.id})")
                handleRequest(exchange, request)
              case Failure(e) =>
                logger.error(s"Failed to parse request: ${e.getMessage}")
                sendJsonRpcError(exchange, "unknown", MCPErrorCodes.PARSE_ERROR, "Parse error")
            }
          } else {
            // It's a notification
            Try(upickleRead[JsonRpcNotification](bodyStr)) match {
              case Success(notification) =>
                logger.debug(s"Notification: ${notification.method}")
                handleNotification(notification)
                // Notifications do not receive a response, but we must acknowledge the HTTP request
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
              case Failure(e) =>
                logger.warn(s"Failed to parse notification: ${e.getMessage}")
                // Even if malformed, for notifications we typically just ignore or log
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
          }

        case Failure(e) if e.getMessage == "Payload too large" =>
          logger.warn("Payload size exceeded limit")
          sendErrorResponse(exchange, 413, "Payload too large")

        case Failure(e) =>
          logger.error(s"Failed to parse JSON: ${e.getMessage}")
          sendJsonRpcError(exchange, "unknown", MCPErrorCodes.PARSE_ERROR, "Parse error")
      }
    }

    private val SupportedVersions = Set("2025-06-18")

    private def handleRequest(exchange: HttpExchange, request: JsonRpcRequest): Unit = {
      // Protocol Version Check
      val protocolValid = if (request.method != "initialize") {
        Option(exchange.getRequestHeaders.getFirst("MCP-Protocol-Version")).exists { version =>
          SupportedVersions.contains(version)
        }
      } else true

      if (!protocolValid) {
        val version = exchange.getRequestHeaders.getFirst("MCP-Protocol-Version")
        sendJsonRpcError(
          exchange,
          request.id,
          MCPErrorCodes.INVALID_PROTOCOL_VERSION,
          s"Unsupported protocol version: $version. Supported: ${SupportedVersions.mkString(", ")}"
        )
      } else {
        val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))

        request.method match {
          case "initialize" => handleInitialize(exchange, request)
          case "tools/list" => handleWithSession(exchange, request, sessionId, handleToolsList)
          case "tools/call" => handleWithSession(exchange, request, sessionId, handleToolsCall)
          case _ => sendJsonRpcError(exchange, request.id, MCPErrorCodes.METHOD_NOT_FOUND, "Method not found")
        }
      }
    }

    private def handleNotification(notification: JsonRpcNotification): Unit =
      notification.method match {
        case "notifications/initialized" =>
          logger.info("Client initialized notification received")
        case _ =>
          logger.debug(s"Received unhandled notification: ${notification.method}")
      }

    private def handleInitialize(exchange: HttpExchange, request: JsonRpcRequest): Unit = {
      val initRequest = request.params
        .flatMap(params => Try(upickleRead[InitializeRequest](params.toString)).toOption)
        .getOrElse(InitializeRequest("2025-06-18", MCPCapabilities(), ClientInfo("unknown", "1.0")))

      // Protocol negotiation
      val clientVersion   = initRequest.protocolVersion
      val protocolVersion = if (SupportedVersions.contains(clientVersion)) clientVersion else "2025-06-18"

      logger.info(s"Initializing with protocol: $protocolVersion")

      // Create session for modern protocols
      val sessionOpt = if (protocolVersion == "2025-06-18") {
        Some(SessionStore.createSession(protocolVersion))
      } else None

      val response = JsonRpcResponse(
        id = request.id,
        result = Some(
          upickle.default.writeJs(
            InitializeResponse(
              protocolVersion = protocolVersion,
              capabilities = MCPCapabilities(tools = Some(Obj())),
              serverInfo = ServerInfo(options.name, options.version)
            )
          )
        )
      )

      sendJsonRpcResponse(exchange, response, sessionOpt.map(_.id))
    }

    private def handleWithSession(
      exchange: HttpExchange,
      request: JsonRpcRequest,
      sessionId: Option[String],
      handler: JsonRpcRequest => JsonRpcResponse
    ): Unit =
      // Basic session validation
      sessionId match {
        case Some(id) if SessionStore.getSession(id).isEmpty =>
          logger.warn(s"Unknown session: $id")
          sendJsonRpcError(exchange, request.id, MCPErrorCodes.INVALID_REQUEST, s"Invalid session: $id")
        case _ =>
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
      val toolName  = request.params.flatMap(_.obj.get("name")).map(_.str).getOrElse("")
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
                case other        => other.render()
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

    private def sendJsonRpcResponse(
      exchange: HttpExchange,
      response: JsonRpcResponse,
      sessionId: Option[String] = None
    ): Unit = {
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
      }.failed.foreach(e => logger.warn(s"Failed to write response: ${e.getMessage}"))
    }

    private def sendErrorResponse(exchange: HttpExchange, code: Int, message: String): Unit =
      sendResponse(exchange, code, "text/plain", message)

    private def readBodyWithLimit(exchange: HttpExchange, limit: Int): Try[String] =
      Using(exchange.getRequestBody) { is =>
        val buffer    = new Array[Byte](4096)
        val out       = new java.io.ByteArrayOutputStream()
        var total     = 0
        var bytesRead = is.read(buffer)
        var overflow  = false

        while (bytesRead != -1 && !overflow) {
          total += bytesRead
          if (total > limit) {
            overflow = true
          } else {
            out.write(buffer, 0, bytesRead)
            bytesRead = is.read(buffer)
          }
        }

        if (overflow) Failure(new RuntimeException("Payload too large"))
        else Success(out.toString("UTF-8"))
      }.flatten
  }
}
