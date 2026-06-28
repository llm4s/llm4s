package org.llm4s.mcp

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.toolapi.ToolFunction
import org.slf4j.LoggerFactory
import ujson.Obj
import upickle.default.{ read => upickleRead, write => upickleWrite }

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.{
  ConcurrentHashMap,
  ExecutorService,
  LinkedBlockingQueue,
  SynchronousQueue,
  ThreadPoolExecutor,
  TimeUnit
}

import scala.collection.concurrent.TrieMap
import scala.util.{ Failure, Success, Try, Using }

/**
 * Configuration options for the MCPServer.
 *
 * @param port    Port to bind to. Use 0 for a random available port.
 * @param path    Base path for MCP endpoints (e.g. "/mcp").
 * @param name    Server name reported during MCP initialization.
 * @param version Server version reported during MCP initialization.
 * @param apiKey  Optional API key for Bearer-token authentication.
 *                When set, every request must include `Authorization: Bearer <key>`.
 *                Configuring an API key removes the development-only security
 *                warning and allows binding to non-localhost interfaces via `host`.
 * @param host    Network interface to bind to (default "127.0.0.1").
 *                Change to "0.0.0.0" (or a specific IP) only when `apiKey` is set;
 *                `start()` refuses to bind a non-loopback host without an `apiKey`.
 */
case class MCPServerOptions(
  port: Int,
  path: String,
  name: String,
  version: String,
  apiKey: Option[String] = None,
  host: String = "127.0.0.1"
)

/**
 * A generic, reusable Model Context Protocol (MCP) Server.
 *
 * Exposes a list of llm4s `ToolFunction`s via two transport protocols:
 *
 *  - **MCP 2025-06-18 Streamable HTTP** — POST / DELETE `{path}`
 *  - **MCP 2024-11-05 HTTP+SSE** — GET `{path}/sse` + POST `{path}/messages?sessionId=…`
 *    (required by Claude Desktop and other legacy MCP clients)
 *
 * === Authentication ===
 * Set `MCPServerOptions.apiKey` to enable Bearer-token authentication.  When set,
 * every HTTP request must include `Authorization: Bearer <key>`; unauthenticated
 * requests receive HTTP 401.  Without an API key the server logs a development-only
 * security warning, and `start()` refuses to bind to a non-loopback `host` (returning
 * a `Left`) so an unauthenticated server is never exposed to the network.
 *
 * === Concurrency ===
 * Connections are served by an elastic, bounded thread pool capped at
 * [[MCPServer.MaxServerThreads]] threads (shared across long-lived SSE streams and
 * short Streamable HTTP requests).  Beyond that ceiling new connections are rejected
 * until capacity frees up, providing backpressure under load.
 *
 * {{{
 * val tools   = Seq(myTool1, myTool2)
 * val options = MCPServerOptions(8080, "/mcp", "MyServer", "1.0", apiKey = Some("secret"))
 * val server  = new MCPServer(options, tools)
 * server.start()
 * }}}
 *
 * @param options Server configuration.
 * @param tools   List of tools to expose.
 */
class MCPServer(
  options: MCPServerOptions,
  tools: Seq[ToolFunction[_, _]]
) {
  private val logger                           = LoggerFactory.getLogger(getClass)
  private var server: Option[HttpServer]       = None
  private var executorService: ExecutorService = _

  private val toolMap: Map[String, ToolFunction[_, _]] = tools.map(t => t.name -> t).toMap

  // SSE connection management – one entry per open SSE connection
  private case class SSEConnection(
    sessionId: String,
    queue: LinkedBlockingQueue[Option[String]] // None is the close signal
  )
  private val sseConnections = new ConcurrentHashMap[String, SSEConnection]()

  def boundPort: Int = server.map(_.getAddress.getPort).getOrElse(-1)
  def getPort: Int   = boundPort

  def start(): Either[Exception, Unit] = synchronized {
    if (server.isDefined) {
      logger.warn("MCPServer is already running")
      return Right(())
    }

    if (options.apiKey.isEmpty && !isLoopbackHost(options.host)) {
      val msg =
        s"Refusing to start MCPServer: host '${options.host}' is not loopback but no apiKey is configured. " +
          "Binding a public interface without authentication would expose all tools to the network. " +
          "Set MCPServerOptions.apiKey to enable bearer-token authentication, or bind to 127.0.0.1."
      logger.error(msg)
      return Left(new IllegalStateException(msg))
    }

    if (options.apiKey.isDefined) {
      logger.info("MCPServer starting with API key authentication enabled")
    } else {
      logger.warn("!!! SECURITY WARNING !!!")
      logger.warn("This server is intended for local development only. Do not expose to untrusted networks.")
      logger.warn("Set apiKey in MCPServerOptions to enable bearer-token authentication for production use.")
    }

    Try {
      val httpServer = HttpServer.create(new InetSocketAddress(options.host, options.port), 0)
      httpServer.createContext(options.path, new MCPHandler)
      // Bounded elastic pool: threads are created on demand (like a cached pool, so long-lived
      // SSE GET connections never starve short Streamable HTTP requests) but capped at
      // MaxServerThreads so the pool retains a backpressure ceiling under many concurrent connections.
      executorService = new ThreadPoolExecutor(
        0,
        MCPServer.MaxServerThreads,
        60L,
        TimeUnit.SECONDS,
        new SynchronousQueue[Runnable]()
      )
      httpServer.setExecutor(executorService)
      httpServer.start()
      server = Some(httpServer)
      val actualPort = httpServer.getAddress.getPort
      logger.info(s"MCPServer '${options.name}' started on http://${options.host}:$actualPort${options.path}")
      logger.info(s"Exposing ${tools.size} tools: ${tools.map(_.name).mkString(", ")}")
      logger.info(s"SSE transport (2024-11-05) at http://${options.host}:$actualPort${options.path}/sse")
    }.toEither.left.map { case e: Exception =>
      logger.error(s"Failed to start MCPServer: ${e.getMessage}", e)
      e
    }
  }

  // A non-loopback host without an apiKey would expose all tools to the network, so start() rejects it.
  private def isLoopbackHost(host: String): Boolean =
    host == "127.0.0.1" ||
      host == "::1" ||
      host == "0:0:0:0:0:0:0:1" ||
      host.equalsIgnoreCase("localhost")

  def stop(delay: Int = 0): Unit = synchronized {
    server.foreach { s =>
      logger.info("Stopping MCPServer...")
      // Close all open SSE connections gracefully
      sseConnections.values().forEach(conn => Try(conn.queue.put(None)))
      sseConnections.clear()
      s.stop(delay)
      if (executorService != null) {
        executorService.shutdown()
        Try {
          if (!executorService.awaitTermination((delay + 5).toLong, TimeUnit.SECONDS)) {
            executorService.shutdownNow()
          }
        }.recover { case _: InterruptedException => executorService.shutdownNow() }
        executorService = null
      }
      server = None
    }
  }

  // HTTP session store for Streamable HTTP transport (2025-06-18)
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

  // Shared tool execution – used by both Streamable HTTP and SSE transports

  private def handleToolsList(request: JsonRpcRequest): JsonRpcResponse = {
    val mcpTools = tools.map { tool =>
      MCPTool(
        name = tool.name,
        description = tool.description,
        inputSchema = tool.toOpenAITool(strict = false)("function")("parameters")
      )
    }
    JsonRpcResponse(id = request.id, result = Some(upickle.default.writeJs(ToolsListResponse(mcpTools))))
  }

  private def handleToolsCall(request: JsonRpcRequest): JsonRpcResponse = {
    val toolName  = request.params.flatMap(_.obj.get("name")).map(_.str).getOrElse("")
    val arguments = request.params.flatMap(_.obj.get("arguments")).getOrElse(ujson.Obj())
    logger.info(s"Executing tool: $toolName")
    toolMap.get(toolName) match {
      case Some(tool) =>
        tool.execute(arguments) match {
          case Right(resultJson) =>
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
            logger.error(s"Tool execution failed: $toolName - $error")
            JsonRpcResponse(
              id = request.id,
              error = Some(JsonRpcError(MCPErrorCodes.TOOL_EXECUTION_ERROR, s"Tool failed: $error", None))
            )
        }
      case None =>
        JsonRpcResponse(
          id = request.id,
          error = Some(JsonRpcError(MCPErrorCodes.TOOL_NOT_FOUND, s"Tool not found: $toolName", None))
        )
    }
  }

  // Dispatch a JSON-RPC request for the SSE transport (no HTTP session management)
  private def processSSERequest(request: JsonRpcRequest): JsonRpcResponse =
    request.method match {
      case "initialize" =>
        val initReq = request.params
          .flatMap(p => Try(upickleRead[InitializeRequest](p.toString)).toOption)
          .getOrElse(InitializeRequest("2024-11-05", MCPCapabilities(), ClientInfo("unknown", "1.0")))
        logger.info(s"SSE initialize: client=${initReq.clientInfo.name}, protocol=${initReq.protocolVersion}")
        JsonRpcResponse(
          id = request.id,
          result = Some(
            upickle.default.writeJs(
              InitializeResponse(
                protocolVersion = "2024-11-05",
                capabilities = MCPCapabilities(tools = Some(Obj())),
                serverInfo = ServerInfo(options.name, options.version)
              )
            )
          )
        )
      case "tools/list" => handleToolsList(request)
      case "tools/call" => handleToolsCall(request)
      case other =>
        JsonRpcResponse(
          id = request.id,
          error = Some(JsonRpcError(MCPErrorCodes.METHOD_NOT_FOUND, s"Method not found: $other", None))
        )
    }

  // HTTP handler – entry point for all incoming requests
  private class MCPHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      // Authentication gate – checked before any routing
      if (!isAuthorized(exchange)) {
        exchange.getResponseHeaders.set("WWW-Authenticate", "Bearer")
        sendErrorResponse(exchange, 401, "Unauthorized")
        return
      }

      val method = exchange.getRequestMethod
      val path   = exchange.getRequestURI.getPath
      logger.debug(s"$method $path")

      Try {
        (method, path) match {
          case ("GET", p) if p.endsWith("/sse")       => handleSSEGet(exchange)
          case ("POST", p) if p.endsWith("/messages") => handleSSEPost(exchange)
          case ("POST", _)                            => handlePOST(exchange)
          case ("DELETE", _)                          => handleDELETE(exchange)
          case _                                      => sendErrorResponse(exchange, 405, "Method not allowed")
        }
      }.recover { case e =>
        logger.error(s"Unhandled error in $method $path: ${e.getMessage}", e)
        Try(sendErrorResponse(exchange, 500, "Internal server error"))
      }
    }

    private def isAuthorized(exchange: HttpExchange): Boolean =
      options.apiKey match {
        case None => true
        case Some(key) =>
          Option(exchange.getRequestHeaders.getFirst("Authorization"))
            .map(_.trim)
            .exists { header =>
              header.length > 7 &&
              header.substring(0, 7).equalsIgnoreCase("Bearer ") &&
              // Constant-time comparison: avoids leaking the key via response-timing side-channels.
              MessageDigest.isEqual(
                header.substring(7).getBytes(StandardCharsets.UTF_8),
                key.getBytes(StandardCharsets.UTF_8)
              )
            }
      }

    // ── SSE transport (MCP 2024-11-05) ──────────────────────────────────────

    private def handleSSEGet(exchange: HttpExchange): Unit = {
      val sessionId = UUID.randomUUID().toString
      val queue     = new LinkedBlockingQueue[Option[String]]()
      sseConnections.put(sessionId, SSEConnection(sessionId, queue))

      // Build the POST-target URL that the client will use for requests
      val localPort  = exchange.getLocalAddress.getPort
      val hostForUrl = if (options.host == "0.0.0.0") "127.0.0.1" else options.host
      val messageUrl = s"http://$hostForUrl:$localPort${options.path}/messages?sessionId=$sessionId"

      exchange.getResponseHeaders.set("Content-Type", "text/event-stream")
      exchange.getResponseHeaders.set("Cache-Control", "no-cache")
      exchange.getResponseHeaders.set("Connection", "keep-alive")
      exchange.sendResponseHeaders(200, 0) // 0 = chunked transfer encoding

      val os = exchange.getResponseBody

      def writeEvent(event: String): Boolean =
        Try { os.write(event.getBytes("UTF-8")); os.flush() }.isSuccess

      // scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordFinally
      // Manual try/finally (not Using) because the "resource" is the long-lived streaming loop
      // below: cleanup must run when the poll loop exits normally OR the client disconnects
      // mid-stream, which does not fit Using's single-expression scope. Suppression is limited to
      // this handler.
      try {
        // Per MCP 2024-11-05 spec: first SSE event tells the client where to POST
        if (!writeEvent(s"event: endpoint\ndata: $messageUrl\n\n")) {
          logger.warn(s"SSE($sessionId) client disconnected before endpoint event")
          return
        }
        logger.info(s"SSE connection opened: sessionId=$sessionId, messageUrl=$messageUrl")

        var running = true
        while (running)
          queue.poll(30L, TimeUnit.SECONDS) match {
            case null        => running = writeEvent(": keepalive\n\n")
            case None        => running = false // close signal from stop()
            case Some(event) => running = writeEvent(event)
          }
      } finally {
        sseConnections.remove(sessionId)
        Try(os.close())
        exchange.close()
        logger.info(s"SSE connection closed: sessionId=$sessionId")
      }
      // scalafix:on
    }

    private def handleSSEPost(exchange: HttpExchange): Unit = {
      val sessionId = Option(exchange.getRequestURI.getQuery)
        .getOrElse("")
        .split("&")
        .find(_.startsWith("sessionId="))
        .map(_.stripPrefix("sessionId="))

      sessionId match {
        case None =>
          sendErrorResponse(exchange, 400, "Missing sessionId query parameter")
        case Some(sid) =>
          Option(sseConnections.get(sid)) match {
            case None =>
              sendErrorResponse(exchange, 400, s"Unknown SSE session: $sid")
            case Some(conn) =>
              val result = for {
                bodyStr <- readBodyWithLimit(exchange, MaxPayloadSize)
                json    <- Try(ujson.read(bodyStr))
              } yield (bodyStr, json)

              result match {
                case Failure(e) if Option(e.getMessage).contains("Payload too large") =>
                  sendErrorResponse(exchange, 413, "Payload too large")
                case Failure(e) =>
                  logger.error(s"SSE($sid) bad request: ${e.getMessage}")
                  sendErrorResponse(exchange, 400, "Bad request")
                case Success((bodyStr, json)) =>
                  if (json.obj.contains("id")) {
                    Try(upickleRead[JsonRpcRequest](bodyStr)) match {
                      case Failure(e) =>
                        sendErrorResponse(exchange, 400, s"Invalid JSON-RPC: ${e.getMessage}")
                      case Success(request) =>
                        logger.debug(s"SSE($sid) request: ${request.method} (id=${request.id})")
                        val response = processSSERequest(request)
                        val sseEvent = s"event: message\ndata: ${upickleWrite(response)}\n\n"
                        conn.queue.put(Some(sseEvent))
                        // POST returns 202 Accepted; the actual response is delivered via SSE stream
                        exchange.sendResponseHeaders(202, -1)
                        exchange.close()
                    }
                  } else {
                    // JSON-RPC notification – acknowledge only, no response
                    Try(upickleRead[JsonRpcNotification](bodyStr)).foreach(handleNotification)
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                  }
              }
          }
      }
    }

    // ── Streamable HTTP transport (MCP 2025-06-18) ──────────────────────────

    private val MaxPayloadSize = 10 * 1024 * 1024

    private val SupportedVersions = Set("2025-06-18")

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
            Try(upickleRead[JsonRpcRequest](bodyStr)) match {
              case Success(request) =>
                logger.debug(s"Request: ${request.method} (id: ${request.id})")
                handleRequest(exchange, request)
              case Failure(e) =>
                logger.error(s"Failed to parse request: ${e.getMessage}")
                sendJsonRpcError(exchange, "unknown", MCPErrorCodes.PARSE_ERROR, "Parse error")
            }
          } else {
            Try(upickleRead[JsonRpcNotification](bodyStr)) match {
              case Success(notification) =>
                logger.debug(s"Notification: ${notification.method}")
                handleNotification(notification)
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
              case Failure(e) =>
                logger.warn(s"Failed to parse notification: ${e.getMessage}")
                exchange.sendResponseHeaders(200, -1)
                exchange.close()
            }
          }

        case Failure(e) if Option(e.getMessage).contains("Payload too large") =>
          logger.warn("Payload size exceeded limit")
          sendErrorResponse(exchange, 413, "Payload too large")

        case Failure(e) =>
          logger.error(s"Failed to parse JSON: ${e.getMessage}")
          sendJsonRpcError(exchange, "unknown", MCPErrorCodes.PARSE_ERROR, "Parse error")
      }
    }

    private def handleRequest(exchange: HttpExchange, request: JsonRpcRequest): Unit = {
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
        case "notifications/initialized" => logger.info("Client initialized notification received")
        case _                           => logger.debug(s"Received notification: ${notification.method}")
      }

    private def handleInitialize(exchange: HttpExchange, request: JsonRpcRequest): Unit = {
      val initRequest = request.params
        .flatMap(params => Try(upickleRead[InitializeRequest](params.toString)).toOption)
        .getOrElse(InitializeRequest("2025-06-18", MCPCapabilities(), ClientInfo("unknown", "1.0")))

      val clientVersion   = initRequest.protocolVersion
      val protocolVersion = if (SupportedVersions.contains(clientVersion)) clientVersion else "2025-06-18"
      logger.info(s"Initializing with protocol: $protocolVersion")

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
      sessionId match {
        case Some(id) if SessionStore.getSession(id).isEmpty =>
          logger.warn(s"Unknown session: $id")
          sendJsonRpcError(exchange, request.id, MCPErrorCodes.INVALID_REQUEST, s"Invalid session: $id")
        case _ =>
          val response = handler(request)
          sendJsonRpcResponse(exchange, response, sessionId)
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

    // ── HTTP helpers ─────────────────────────────────────────────────────────

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

object MCPServer {

  /**
   * Maximum number of threads (and therefore concurrent connections) the server will service.
   * Threads are created on demand and reaped after 60s idle; beyond this ceiling new connections
   * are rejected until capacity frees up. Long-lived SSE streams each occupy one thread.
   */
  val MaxServerThreads: Int = 256
}
