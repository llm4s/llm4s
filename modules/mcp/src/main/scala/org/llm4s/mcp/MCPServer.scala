package org.llm4s.mcp

import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import org.llm4s.error.CancelledError
import org.llm4s.security.Secret
import org.llm4s.toolapi.ToolFunction
import org.llm4s.util.DurationRounding
import org.slf4j.LoggerFactory

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
import scala.concurrent.duration.*
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
 * @param allowedHosts   Host names, besides the loopback ones, that a request's `Host` header may name (matched
 *                       without its port, ignoring case), for a server reached through a known name. Empty (the
 *                       default): a server bound to a loopback `host` serves only `localhost`, `127.0.0.1` and
 *                       `[::1]`, which is what stops DNS rebinding; a server bound to another interface serves
 *                       any `Host`.
 * @param allowedOrigins Origins (`scheme://host[:port]`, ignoring case) besides the loopback ones whose browser
 *                       requests are served. A request without `Origin` (not from a browser) is unaffected; one
 *                       with any other `Origin`, including `null`, is refused with 403.
 * @param sessionIdleTimeout How long a Streamable HTTP session may go unused before it expires (#3081); a request
 *                           naming an expired session is answered 404, and the client starts a new one
 * @param maxSessions        The most Streamable HTTP sessions kept at once; a new one beyond it evicts the one
 *                           unused for longest
 */
case class MCPServerOptions(
  port: Int,
  path: String,
  name: String,
  version: String,
  apiKey: Option[Secret] = None,
  host: String = "127.0.0.1",
  allowedHosts: Set[String] = Set.empty,
  allowedOrigins: Set[String] = Set.empty,
  sessionIdleTimeout: FiniteDuration = 30.minutes,
  maxSessions: Int = 1000
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
 * === DNS rebinding ===
 * Before the key is checked, a request whose `Host` is not a loopback name (or one of
 * `MCPServerOptions.allowedHosts`) is refused with 403 when the server is bound to a loopback host, and so
 * is any request whose `Origin` is present and neither loopback nor one of `allowedOrigins`. A POST must
 * declare `Content-Type: application/json` (415 otherwise).
 *
 * === Concurrency ===
 * Connections are served by an elastic, bounded thread pool capped at
 * [[MCPServer.MaxServerThreads]] threads (shared across long-lived SSE streams and
 * short Streamable HTTP requests).  Beyond that ceiling new connections are rejected
 * until capacity frees up, providing backpressure under load.
 *
 * {{{
 * val tools   = Seq(myTool1, myTool2)
 * val options = MCPServerOptions(8080, "/mcp", "MyServer", "1.0", apiKey = Some(Secret("secret")))
 * val server  = new MCPServer(options, tools)
 * server.start()
 * }}}
 *
 * === Protocol ===
 * `initialize` accepts the client's version when it is one of [[MCPServer.SupportedVersions]] and answers with the
 * latest otherwise. A later request without `MCP-Protocol-Version` is taken as 2025-03-26, as the specification
 * says; one naming an unsupported version is answered 400. `ping` is answered with an empty result. A request naming
 * a session the server does not know, or one that has expired ([[MCPServerOptions.sessionIdleTimeout]]), is
 * answered 404. A `notifications/cancelled` for a `tools/call` still running on the same session (or SSE stream)
 * interrupts it, and its reply is not sent; a client that uses no session cannot cancel, since nothing would tell
 * its calls from another sessionless client's. The legacy `endpoint` event is a path, so a client reaches the messages endpoint by the name and scheme it
 * used for the stream.
 *
 * @param options     Server configuration.
 * @param tools       List of tools to expose.
 * @param annotations The MCP tool annotations to advertise, by tool name (`readOnlyHint`, `destructiveHint`, ...);
 *                    a tool without an entry is listed without annotations
 */
class MCPServer(
  options: MCPServerOptions,
  tools: Seq[ToolFunction[_]],
  annotations: Map[String, MCPToolAnnotations] = Map.empty
) {
  private val logger                           = LoggerFactory.getLogger(getClass)
  private var server: Option[HttpServer]       = None
  private var executorService: ExecutorService = _

  private val toolMap: Map[String, ToolFunction[_]] = tools.map(t => t.name -> t).toMap

  private val loopbackBound: Boolean           = isLoopbackHost(options.host)
  private val allowedHostNames: Set[String]    = options.allowedHosts.map(_.trim.toLowerCase(java.util.Locale.ROOT))
  private val allowedOriginValues: Set[String] = options.allowedOrigins.map(MCPServer.normalizeOrigin)

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

  /**
   * Stops the server.
   *
   * @param delay the longest to wait for in-flight exchanges to finish before closing; the
   *              underlying `HttpServer` counts it in whole seconds, so it is rounded up
   */
  def stop(delay: FiniteDuration = Duration.Zero): Unit = synchronized {
    server.foreach { s =>
      logger.info("Stopping MCPServer...")
      // Close all open SSE connections gracefully
      sseConnections.values().forEach(conn => Try(conn.queue.put(None)))
      sseConnections.clear()
      s.stop(DurationRounding.ceilSecondsInt(delay))
      if (executorService != null) {
        executorService.shutdown()
        Try {
          if (!executorService.awaitTermination(delay.toMillis + 5000L, TimeUnit.MILLISECONDS)) {
            executorService.shutdownNow()
          }
        }.recover { case _: InterruptedException => executorService.shutdownNow() }
        executorService = null
      }
      server = None
    }
  }

  /** The clock sessions expire by; a spec replaces it. */
  private[mcp] def nowMillis(): Long = System.currentTimeMillis()

  // HTTP session store for the Streamable HTTP transport: a session expires after it has gone unused for
  // sessionIdleTimeout, and at most maxSessions are kept, the one unused for longest giving way (#3081)
  private class Session(val id: String, val protocolVersion: String, @volatile var lastUsed: Long)

  private object SessionStore {
    private val sessions   = TrieMap[String, Session]()
    private val idleMillis = options.sessionIdleTimeout.toMillis

    private def expired(session: Session, now: Long): Boolean = now - session.lastUsed > idleMillis

    def createSession(protocolVersion: String): Session = synchronized {
      val now = nowMillis()
      sessions.foreach { case (id, session) => if (expired(session, now)) sessions.remove(id) }
      while (sessions.size >= math.max(1, options.maxSessions))
        sessions.values.minByOption(_.lastUsed).foreach { oldest =>
          logger.debug("Evicting the MCP session unused for longest: the session limit is reached")
          sessions.remove(oldest.id)
        }
      val session = new Session(UUID.randomUUID().toString, protocolVersion, now)
      sessions(session.id) = session
      logger.debug(s"Created a session for protocol $protocolVersion")
      session
    }

    /** The live session `id` names, marked used now; `None` for an unknown or expired one, which is removed. */
    def touch(id: String): Option[Session] = {
      val now = nowMillis()
      sessions.get(id) match {
        case Some(session) if expired(session, now) =>
          sessions.remove(id)
          None
        case Some(session) =>
          session.lastUsed = now
          Some(session)
        case None => None
      }
    }

    def removeSession(id: String): Boolean = sessions.remove(id).isDefined

    def size: Int = sessions.size
  }

  /** How many Streamable HTTP sessions the server holds. */
  private[mcp] def sessionCount: Int = SessionStore.size

  // ── Cancellation (#3081) ──────────────────────────────────────────────────

  /**
   * One `tools/call` that may be cancelled: the thread running it, and whether it is running, cancelled or completed.
   * Cancelling and completing are mutually exclusive, decided under the call's lock: an interrupt is delivered only
   * by a cancellation that won, and only while the call still runs, so it never reaches a completed call's reply or
   * the next request on the pooled thread.
   */
  final private class RunningCall(val thread: Thread) {
    private var state = 0 // 0 running, 1 cancelled, 2 completed; guarded by this

    /** Marks the call cancelled and interrupts it; `false` when it had already completed. */
    def cancel(): Boolean = synchronized {
      if (state == 0) {
        state = 1
        thread.interrupt()
        true
      } else false
    }

    /** Marks the call completed; `false` when a cancellation won, whose interrupt has then been delivered. */
    def complete(): Boolean = synchronized {
      if (state == 0) { state = 2; true }
      else false
    }
  }

  // The calls that may be cancelled, by the session or SSE stream they came on and their id as sent (a number and a
  // string are different ids). A call without a session is never registered: nothing would tell two such clients'
  // calls apart, so no `notifications/cancelled` can reach one.
  private val runningCalls = new ConcurrentHashMap[(String, ujson.Value), RunningCall]()

  /** Called after a call has run and before it is marked completed; a spec forces an interleaving here. */
  private[mcp] def beforeCallCompletes(): Unit = ()

  /**
   * Runs a `tools/call` so that a `notifications/cancelled` for it on the same channel can interrupt it. `Some` of the
   * reply, or `None` when the call was cancelled: the specification says no reply is sent for a cancelled request.
   * A call on no channel (a sessionless Streamable HTTP client) runs without cancellation.
   */
  private def cancellable(channel: Option[String], id: ujson.Value, request: JsonRpcRequest)(
    run: => JsonRpcResponse
  ): Option[JsonRpcResponse] = {
    val registered = channel.map { c =>
      val key  = c -> id
      val call = new RunningCall(Thread.currentThread())
      runningCalls.put(key, call)
      key -> call
    }
    // A handler that lets the interrupt escape is still a cancelled (or failed) call, never a dead pool thread
    val reply = CancelledError.catchInterrupt(Try(run))
    beforeCallCompletes()
    val completed = registered.forall { case (key, call) =>
      val done = call.complete()
      runningCalls.remove(key, call): Unit
      done
    }
    if (!completed) {
      // The cancellation's interrupt was delivered under the call's lock, before `complete` returned: clear it here,
      // so it never reaches the next request on this pooled thread
      Thread.interrupted(): Unit
      logger.info(s"Tool call ${request.id} was cancelled by the client; its reply is not sent")
      None
    } else
      reply match {
        case Right(Success(response)) => Some(response)
        case Right(Failure(e))        => throw e
        case Left(_) =>
          Some(
            JsonRpcResponse(
              id = request.id,
              error = Some(JsonRpcError(MCPErrorCodes.INTERNAL_ERROR, "Tool call interrupted", None))
            )
          )
      }
  }

  private def cancel(channel: Option[String], notification: JsonRpcNotification): Unit =
    (
      channel,
      notification.params.flatMap(_.objOpt).flatMap(_.get("requestId")).collect {
        case s: ujson.Str => s: ujson.Value
        case n: ujson.Num => n: ujson.Value
      }
    ) match {
      case (Some(c), Some(id)) =>
        Option(runningCalls.get(c -> id)).foreach { call =>
          if (call.cancel()) logger.info(s"Cancelled tool call ${idText(id)} at the client's request")
        }
      case (None, _) => logger.debug("notifications/cancelled without a session: calls without one cannot be cancelled")
      case (_, None) => logger.debug("notifications/cancelled without a requestId")
    }

  // ── JSON-RPC 2.0 envelope, shared by both transports (#3071) ──────────────

  // What a POSTed message is. A request keeps its id as it arrived, a string or a number, so the reply carries the
  // same value back; JsonRpcRequest.id holds its text only for logging and as a tool call's fallback id.
  private enum Incoming:
    case Request(request: JsonRpcRequest, id: ujson.Value)
    case Notification(notification: JsonRpcNotification)
    case ClientResponse
    case Invalid(id: ujson.Value, message: String)

  private def classify(json: ujson.Value): Incoming = json match {
    case _: ujson.Arr => Incoming.Invalid(ujson.Null, "Batch requests are not supported")
    case obj: ujson.Obj =>
      val fields = obj.value
      val id     = fields.get("id")
      val validId = id.collect {
        case s: ujson.Str => s
        case n: ujson.Num => n
      }
      val replyId = validId.getOrElse(ujson.Null)
      val params  = fields.get("params")
      fields.get("method") match {
        case Some(ujson.Str(_)) if !fields.get("jsonrpc").contains(ujson.Str("2.0")) =>
          Incoming.Invalid(replyId, "jsonrpc must be \"2.0\"")
        case Some(_) if params.exists(p => p.objOpt.isEmpty && p.arrOpt.isEmpty) =>
          Incoming.Invalid(replyId, "params must be an object or an array")
        case Some(ujson.Str(method)) =>
          (id, validId) match {
            case (None, _) => Incoming.Notification(JsonRpcNotification("2.0", method, params))
            case (Some(_), Some(value)) =>
              Incoming.Request(JsonRpcRequest("2.0", idText(value), method, params), value)
            case (Some(_), None) => Incoming.Invalid(ujson.Null, "id must be a string or a number")
          }
        case Some(_) => Incoming.Invalid(replyId, "method must be a string")
        case None if validId.isDefined && (fields.contains("result") || fields.contains("error")) =>
          Incoming.ClientResponse
        case None => Incoming.Invalid(replyId, "Invalid Request")
      }
    case _ => Incoming.Invalid(ujson.Null, "Invalid Request")
  }

  private def idText(id: ujson.Value): String = id match {
    case ujson.Str(s) => s
    case other        => other.render()
  }

  // The reply's text: `jsonrpc`, the request's own id (null when it could not be read), then a result or an error.
  private def renderReply(id: ujson.Value, response: JsonRpcResponse): String = {
    val reply = ujson.Obj("jsonrpc" -> "2.0", "id" -> id)
    response.error match {
      case Some(error) => reply("error") = upickle.default.writeJs(error)
      case None        => reply("result") = response.result.getOrElse(ujson.Obj())
    }
    ujson.write(reply)
  }

  private def errorReply(id: ujson.Value, code: Int, message: String): String =
    renderReply(id, JsonRpcResponse(id = idText(id), error = Some(JsonRpcError(code, message, None))))

  // The capabilities this server implements: tools, with no list-changed notifications. Written out, not through
  // MCPCapabilities, whose defaults are a client's (roots, sampling) and whose writer leaves out `tools` (#3071).
  private def initializeResult(protocolVersion: String): ujson.Value =
    ujson.Obj(
      "protocolVersion" -> protocolVersion,
      "capabilities"    -> ujson.Obj("tools" -> ujson.Obj("listChanged" -> false)),
      "serverInfo"      -> upickle.default.writeJs(ServerInfo(options.name, options.version))
    )

  // Shared tool execution – used by both Streamable HTTP and SSE transports

  private def handleToolsList(request: JsonRpcRequest): JsonRpcResponse = {
    val mcpTools = tools.map { tool =>
      MCPTool(
        name = tool.name,
        description = tool.description,
        inputSchema = tool.toolDefinition(strict = false)("function")("parameters"),
        annotations = annotations.get(tool.name)
      )
    }
    JsonRpcResponse(id = request.id, result = Some(upickle.default.writeJs(ToolsListResponse(mcpTools))))
  }

  private def handleToolsCall(request: JsonRpcRequest): JsonRpcResponse = {
    val paramFields = request.params.flatMap(_.objOpt)
    val toolName    = paramFields.flatMap(_.get("name")).flatMap(_.strOpt).getOrElse("")
    val arguments   = paramFields.flatMap(_.get("arguments")).getOrElse(ujson.Obj())
    logger.info(s"Executing tool: $toolName")
    toolMap.get(toolName) match {
      case Some(tool) =>
        MCPToolCallMeta
          .fromParams(request.params, request.id)
          .fold(tool.execute(arguments))(tool.execute(arguments, _)) match {
          case Right(resultJson) =>
            val resultString = resultJson match {
              case ujson.Str(s) => s
              case other        => other.render()
            }
            val response = ToolsCallResponse(
              content = Seq(MCPContent(`type` = "text", text = Some(resultString))),
              isError = Some(false)
            )
            val body = upickle.default.writeJs(response)
            // An object result travels as structuredContent too, so a client gets the value, not its rendering. Only an
            // object: the specification types structuredContent as one, and a schema-validating client rejects a
            // number, array or null there. Any other result is its JSON text alone.
            resultJson match {
              case obj: ujson.Obj => body.obj("structuredContent") = obj
              case _              => ()
            }
            JsonRpcResponse(id = request.id, result = Some(body))
          case Left(error) =>
            // A tool-level failure is a normal result flagged isError (MCP specification, tools/call); a JSON-RPC
            // error is for a protocol problem such as an unknown tool.
            logger.error(s"Tool execution failed: $toolName - ${error.message}")
            val response = ToolsCallResponse(
              content = Seq(MCPContent(`type` = "text", text = Some(error.message))),
              isError = Some(true)
            )
            JsonRpcResponse(id = request.id, result = Some(upickle.default.writeJs(response)))
        }
      case None =>
        JsonRpcResponse(
          id = request.id,
          // An unknown tool is invalid params (MCP specification, tools/call error handling)
          error = Some(JsonRpcError(MCPErrorCodes.INVALID_PARAMS, s"Unknown tool: $toolName", None))
        )
    }
  }

  /**
   * The version to answer `initialize` with: the client's when it is supported, otherwise `fallback` (the latest on
   * Streamable HTTP, 2024-11-05 on the legacy transport, which is that version's).
   */
  private def negotiate(request: JsonRpcRequest, fallback: String = MCPServer.LatestVersion): String = {
    val offered = request.params.flatMap(_.objOpt).flatMap(_.get("protocolVersion")).flatMap(_.strOpt)
    offered.filter(MCPServer.SupportedVersions.contains).getOrElse(fallback)
  }

  // Dispatch a JSON-RPC request for the SSE transport (no HTTP session management); `None` for a cancelled call
  private def processSSERequest(channel: String, id: ujson.Value, request: JsonRpcRequest): Option[JsonRpcResponse] =
    request.method match {
      case "initialize" =>
        val version = negotiate(request, "2024-11-05")
        logger.info(s"SSE initialize: protocol $version")
        Some(JsonRpcResponse(id = request.id, result = Some(initializeResult(version))))
      case "ping"       => Some(JsonRpcResponse(id = request.id, result = Some(ujson.Obj())))
      case "tools/list" => Some(handleToolsList(request))
      case "tools/call" => cancellable(Some(channel), id, request)(handleToolsCall(request))
      case other =>
        Some(
          JsonRpcResponse(
            id = request.id,
            error = Some(JsonRpcError(MCPErrorCodes.METHOD_NOT_FOUND, s"Method not found: $other", None))
          )
        )
    }

  // HTTP handler – entry point for all incoming requests
  private class MCPHandler extends HttpHandler {
    override def handle(exchange: HttpExchange): Unit = {
      // DNS-rebinding gate (#3069), before the key: a rebound page is same-origin to the browser, so only the
      // name it used (Host) and the page's origin (Origin) give it away.
      if (exchange.getRequestHeaders.getFirst("Host") == null) {
        // RFC 9112 section 3.2: a request without Host is answered 400. Newer JDK HttpServers send that themselves
        // before any handler runs; this makes an older one answer the same.
        sendErrorResponse(exchange, 400, "Bad Request: missing Host header")
        return
      }
      if (!MCPServer.hostAllowed(exchange.getRequestHeaders.getFirst("Host"), loopbackBound, allowedHostNames)) {
        sendErrorResponse(exchange, 403, "Forbidden: Host not allowed")
        return
      }
      if (!MCPServer.originAllowed(exchange.getRequestHeaders.getFirst("Origin"), allowedOriginValues)) {
        sendErrorResponse(exchange, 403, "Forbidden: Origin not allowed")
        return
      }

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
          case ("POST", _) if !MCPServer.isJson(exchange.getRequestHeaders.getFirst("Content-Type")) =>
            // A JSON-RPC body is application/json. Requiring it also keeps a cross-site form or text/plain POST,
            // which a browser sends without a preflight, from reaching a tool (#3069).
            sendErrorResponse(exchange, 415, "Unsupported Media Type: Content-Type must be application/json")
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
                key.reveal.getBytes(StandardCharsets.UTF_8)
              )
            }
      }

    // ── SSE transport (MCP 2024-11-05) ──────────────────────────────────────

    private def handleSSEGet(exchange: HttpExchange): Unit = {
      val sessionId = UUID.randomUUID().toString
      val queue     = new LinkedBlockingQueue[Option[String]]()
      sseConnections.put(sessionId, SSEConnection(sessionId, queue))

      // Where the client POSTs: a path (#3081), which it resolves against the stream's URL, so it keeps the scheme,
      // host and port it reached the server by (a server bound to 0.0.0.0, or behind TLS, has no loopback URL to give)
      val messageUrl = s"${options.path}/messages?sessionId=$sessionId"

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
        logger.info("SSE connection opened")

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
        logger.info("SSE connection closed")
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
              // 404, as for a Streamable HTTP session: the client opens a new stream (#3081)
              sendErrorResponse(exchange, 404, "Unknown SSE session")
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
                case Success((_, json)) =>
                  classify(json) match {
                    case Incoming.Request(request, id) =>
                      logger.debug(s"SSE request: ${request.method} (id=${request.id})")
                      // POST returns 202 Accepted at once; the reply is delivered via the SSE stream, so a long tool
                      // call does not hold the POST, and a cancellation can arrive while it runs
                      sendAccepted(exchange)
                      processSSERequest(s"sse:$sid", id, request).foreach { reply =>
                        conn.queue.put(Some(s"event: message\ndata: ${renderReply(id, reply)}\n\n"))
                      }
                    case Incoming.Notification(notification) =>
                      handleNotification(Some(s"sse:$sid"), notification)
                      sendAccepted(exchange)
                    case Incoming.ClientResponse =>
                      // A response to a server request: this server sends none, so there is nothing to match it to.
                      sendAccepted(exchange)
                    case Incoming.Invalid(id, message) =>
                      sendResponse(
                        exchange,
                        400,
                        "application/json",
                        errorReply(id, MCPErrorCodes.INVALID_REQUEST, message)
                      )
                  }
              }
          }
      }
    }

    // ── Streamable HTTP transport (MCP 2025-06-18) ──────────────────────────

    private val MaxPayloadSize = 10 * 1024 * 1024

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
        case Success((_, json)) =>
          classify(json) match {
            case Incoming.Request(request, id) =>
              logger.debug(s"Request: ${request.method} (id: ${request.id})")
              handleRequest(exchange, request, id)
            case Incoming.Notification(notification) =>
              logger.debug(s"Notification: ${notification.method}")
              withLiveSession(exchange) { channel =>
                handleNotification(channel, notification)
                // Streamable HTTP: an accepted notification or response is 202 with no body.
                sendAccepted(exchange)
              }
            case Incoming.ClientResponse =>
              // A response to a server request: this server sends none, so there is nothing to match it to.
              withLiveSession(exchange)(_ => sendAccepted(exchange))
            case Incoming.Invalid(id, message) =>
              logger.warn(s"Invalid JSON-RPC message: $message")
              sendResponse(exchange, 400, "application/json", errorReply(id, MCPErrorCodes.INVALID_REQUEST, message))
          }

        case Failure(e) if Option(e.getMessage).contains("Payload too large") =>
          logger.warn("Payload size exceeded limit")
          sendErrorResponse(exchange, 413, "Payload too large")

        case Failure(e) =>
          logger.error(s"Failed to parse JSON: ${e.getMessage}")
          sendResponse(
            exchange,
            400,
            "application/json",
            errorReply(ujson.Null, MCPErrorCodes.PARSE_ERROR, "Parse error")
          )
      }
    }

    private def handleRequest(exchange: HttpExchange, request: JsonRpcRequest, id: ujson.Value): Unit =
      if (request.method == "initialize") handleInitialize(exchange, request, id)
      else {
        // Absent, the version is 2025-03-26 (specification, "Protocol Version Header"); an unsupported one is a 400
        val version = Option(exchange.getRequestHeaders.getFirst("MCP-Protocol-Version")).map(_.trim)
        version.filterNot(MCPServer.SupportedVersions.contains) match {
          case Some(unsupported) =>
            sendResponse(
              exchange,
              400,
              "application/json",
              errorReply(
                id,
                MCPErrorCodes.INVALID_PROTOCOL_VERSION,
                s"Unsupported protocol version: $unsupported. Supported: ${MCPServer.SupportedVersions.mkString(", ")}"
              )
            )
          case None =>
            withLiveSession(exchange, id) { channel =>
              val sessionId = Option(exchange.getRequestHeaders.getFirst("mcp-session-id"))
              request.method match {
                case "ping" =>
                  sendJsonRpcResponse(
                    exchange,
                    id,
                    JsonRpcResponse(id = request.id, result = Some(ujson.Obj())),
                    sessionId
                  )
                case "tools/list" => sendJsonRpcResponse(exchange, id, handleToolsList(request), sessionId)
                case "tools/call" =>
                  cancellable(channel, id, request)(handleToolsCall(request)) match {
                    case Some(reply) => sendJsonRpcResponse(exchange, id, reply, sessionId)
                    case None        => sendAccepted(exchange) // cancelled: no JSON-RPC reply
                  }
                case _ => sendJsonRpcError(exchange, id, MCPErrorCodes.METHOD_NOT_FOUND, "Method not found")
              }
            }
        }
      }

    /**
     * Runs `serve` with the message's channel (its session, `None` for a client that uses none) when the session it
     * names is live; a session the server does not know or that has expired is answered 404 (#3081), which tells the
     * client to start a new one.
     */
    private def withLiveSession(exchange: HttpExchange, id: ujson.Value = ujson.Null)(
      serve: Option[String] => Unit
    ): Unit =
      Option(exchange.getRequestHeaders.getFirst("mcp-session-id")).map(_.trim) match {
        case Some(session) if SessionStore.touch(session).isEmpty =>
          logger.warn("A request named an unknown or expired session")
          // 404 with a JSON-RPC error, as the reference SDKs answer it
          sendResponse(
            exchange,
            404,
            "application/json",
            errorReply(id, MCPErrorCodes.SESSION_EXPIRED, "Session not found")
          )
        case Some(session) => serve(Some(session))
        case None          => serve(None)
      }

    private def handleNotification(channel: Option[String], notification: JsonRpcNotification): Unit =
      notification.method match {
        case "notifications/initialized" => logger.info("Client initialized notification received")
        case "notifications/cancelled"   => cancel(channel, notification)
        case _                           => logger.debug(s"Received notification: ${notification.method}")
      }

    private def handleInitialize(exchange: HttpExchange, request: JsonRpcRequest, id: ujson.Value): Unit = {
      val protocolVersion = negotiate(request)
      logger.info(s"Initializing with protocol: $protocolVersion")
      val session  = SessionStore.createSession(protocolVersion)
      val response = JsonRpcResponse(id = request.id, result = Some(initializeResult(protocolVersion)))
      sendJsonRpcResponse(exchange, id, response, Some(session.id))
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

    private def sendJsonRpcError(exchange: HttpExchange, id: ujson.Value, code: Int, message: String): Unit =
      sendResponse(exchange, 200, "application/json", errorReply(id, code, message))

    private def sendJsonRpcResponse(
      exchange: HttpExchange,
      id: ujson.Value,
      response: JsonRpcResponse,
      sessionId: Option[String]
    ): Unit = {
      sessionId.foreach(session => exchange.getResponseHeaders.set("mcp-session-id", session))
      sendResponse(exchange, 200, "application/json", renderReply(id, response))
    }

    private def sendAccepted(exchange: HttpExchange): Unit = {
      exchange.sendResponseHeaders(202, -1)
      exchange.close()
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

  /** The protocol versions the server speaks, newest first in [[LatestVersion]]. */
  val SupportedVersions: Set[String] = Set("2025-06-18", "2025-03-26", "2024-11-05")

  /** The version `initialize` answers with when the client offers one the server does not speak. */
  val LatestVersion: String = "2025-06-18"

  /**
   * Maximum number of threads (and therefore concurrent connections) the server will service.
   * Threads are created on demand and reaped after 60s idle; beyond this ceiling new connections
   * are rejected until capacity frees up. Long-lived SSE streams each occupy one thread.
   */
  val MaxServerThreads: Int = 256

  // ── DNS-rebinding checks (#3069) ───────────────────────────────────────────

  private val LoopbackIPv4 = """127\.\d{1,3}\.\d{1,3}\.\d{1,3}""".r

  /** A loopback host name as it appears in a `Host` header or an origin: no DNS lookup, so nothing to rebind. */
  private def isLoopbackName(name: String): Boolean =
    name == "localhost" || name == "[::1]" || LoopbackIPv4.matches(name)

  /** The host name a `Host` header names, without its port, in lower case: `[::1]:8080` is `[::1]`. */
  private[mcp] def hostName(header: String): String = {
    val value = header.trim.toLowerCase(java.util.Locale.ROOT)
    if (value.startsWith("[")) value.indexOf(']') match {
      case -1  => value
      case end => value.substring(0, end + 1)
    }
    else
      value.indexOf(':') match {
        case -1    => value
        case colon => value.substring(0, colon)
      }
  }

  /**
   * Whether a request naming `header` as its `Host` is served. A loopback name always is. A server bound to a
   * loopback interface, or given `allowedHosts`, serves no other name except those: a DNS-rebound page names its
   * own. A server bound to another interface without `allowedHosts` cannot know its names, and serves any.
   */
  private[mcp] def hostAllowed(header: String, loopbackBound: Boolean, allowed: Set[String]): Boolean =
    Option(header).map(hostName) match {
      case Some(name) if isLoopbackName(name) || allowed.contains(name) => true
      case _                                                            => !loopbackBound && allowed.isEmpty
    }

  private[mcp] def normalizeOrigin(origin: String): String =
    origin.trim.toLowerCase(java.util.Locale.ROOT).stripSuffix("/")

  /** Whether a request with this `Origin` is served: none (not a browser), a loopback origin, or an allowed one. */
  private[mcp] def originAllowed(header: String, allowed: Set[String]): Boolean =
    Option(header).map(normalizeOrigin) match {
      case None => true
      case Some(origin) =>
        allowed.contains(origin) ||
        Try(java.net.URI.create(origin)).toOption.exists { uri =>
          Set("http", "https").contains(uri.getScheme) && Option(uri.getHost).exists(isLoopbackName)
        }
    }

  /** Whether a `Content-Type` header declares JSON: `application/json`, with any parameters. */
  private[mcp] def isJson(contentType: String): Boolean =
    Option(contentType).exists(_.split(';')(0).trim.equalsIgnoreCase("application/json"))
}
