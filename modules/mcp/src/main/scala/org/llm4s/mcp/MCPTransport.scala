package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import scala.util.Try
import org.slf4j.Logger
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.auth.ApiKeyProvider

// Transport type definitions

/**
 * Base trait for MCP transport mechanism configurations.
 *
 * Defines how to connect to an MCP server. Each transport type
 * specifies the connection parameters and protocol to use.
 *
 * @see [[StdioTransport]] for subprocess communication
 * @see [[SSETransport]] for legacy HTTP/SSE transport
 * @see [[StreamableHTTPTransport]] for modern HTTP transport
 */
sealed trait MCPTransport {

  /** Unique identifier for this transport instance */
  def name: String
}

/**
 * Stdio transport configuration using subprocess communication.
 *
 * Launches the MCP server as a subprocess and communicates via stdin/stdout.
 * Suitable for local MCP servers and development environments.
 *
 * The server does not inherit the JVM's environment (#3070): it starts with a small allowlist
 * ([[StdioEnvironment.Allowlist]]: `PATH`, `HOME`, `USER`, `LANG`, `TMPDIR` and their Windows counterparts) plus
 * `env`, so an `OPENAI_API_KEY` or cloud credential of the application never reaches a third-party server unless
 * declared.
 *
 * @param command Command line to launch the server process (e.g., `Seq("npx", "@playwright/mcp@1.2.3")`)
 * @param name Unique identifier for this transport instance
 * @param env Variables to set for the server, over the allowlisted ones it inherits: the keys it needs, say
 * @param workingDirectory The directory to start the server in; `None` is the JVM's
 * @param inheritEnvironment `true` passes the JVM's whole environment, plus `env`, as before 1.1. Only for a server
 *                           you trust with every secret the application holds
 */
case class StdioTransport(
  command: Seq[String],
  name: String,
  env: Map[String, String] = Map.empty,
  workingDirectory: Option[java.nio.file.Path] = None,
  inheritEnvironment: Boolean = false
) extends MCPTransport

/**
 * The environment a stdio MCP server starts with (#3070), as the official MCP SDKs do it: the JVM's variables on
 * [[Allowlist]] (matched ignoring case, since Windows names are, as in `Path`), then the declared ones.
 */
private[mcp] object StdioEnvironment {

  /** What a server process needs to find programs, a home, a temp directory and a locale, on POSIX and Windows. */
  val Allowlist: Set[String] = Set(
    // POSIX
    "PATH",
    "HOME",
    "USER",
    "LOGNAME",
    "SHELL",
    "TERM",
    "LANG",
    "LC_ALL",
    "LC_CTYPE",
    "TMPDIR",
    "TZ",
    // Windows
    "APPDATA",
    "LOCALAPPDATA",
    "HOMEDRIVE",
    "HOMEPATH",
    "USERNAME",
    "USERPROFILE",
    "SYSTEMDRIVE",
    "SYSTEMROOT",
    "WINDIR",
    "COMSPEC",
    "PATHEXT",
    "PROCESSOR_ARCHITECTURE",
    "PROGRAMFILES",
    "TEMP",
    "TMP"
  )

  /** Narrows `child` (a `ProcessBuilder`'s environment, the JVM's to begin with) to the allowlist, then adds `declared`. */
  def prepare(child: java.util.Map[String, String], declared: Map[String, String], inherit: Boolean): Unit = {
    if (!inherit) child.keySet.removeIf(name => !Allowlist.contains(name.toUpperCase(java.util.Locale.ROOT))): Unit
    declared.foreach { case (name, value) => child.put(name, value) }
  }
}

/**
 * How the client reaches an HTTP MCP server (#3078): the headers sent with every request, the credential, and the
 * HTTP client that carries them.
 *
 * Build one with [[MCPHttpOptions.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers. `toString` shows header names, never their values.
 *
 * @param headers    sent with every request to the server: each POST, the event-stream `GET` of HTTP+SSE and the
 *                   session `DELETE`. The transport's own headers (`Content-Type`, `Accept`, `mcp-session-id`,
 *                   `MCP-Protocol-Version`) take precedence over one of the same name
 * @param apiKey     read before each request and sent as `Authorization: Bearer <key>`, over an `Authorization` in
 *                   `headers`; a key that cannot be read fails the request. `MCPServerOptions.apiKey` is what an
 *                   llm4s `MCPServer` checks
 * @param httpClient the client to send with, for the application's proxy and TLS settings
 *                   (`Llm4sHttpClient.create(HttpClientOptions(network))`). The transport never closes a client it
 *                   was given; with `None` it creates one and closes it when it is closed
 */
final case class MCPHttpOptions private (
  headers: Map[String, String],
  apiKey: Option[ApiKeyProvider],
  httpClient: Option[Llm4sHttpClient]
) {

  /** Replaces the headers sent with every request. */
  def withHeaders(headers: Map[String, String]): MCPHttpOptions = copy(headers = headers)

  /** Adds or replaces one header sent with every request. */
  def withHeader(name: String, value: String): MCPHttpOptions = copy(headers = headers + (name -> value))

  /** Sends the key `apiKey` provides as `Authorization: Bearer`. */
  def withApiKey(apiKey: ApiKeyProvider): MCPHttpOptions = copy(apiKey = Some(apiKey))

  /** Sets or clears the credential sent as `Authorization: Bearer`. */
  def withApiKey(apiKey: Option[ApiKeyProvider]): MCPHttpOptions = copy(apiKey = apiKey)

  /** Sends with `httpClient`, which the transport never closes. */
  def withHttpClient(httpClient: Llm4sHttpClient): MCPHttpOptions = copy(httpClient = Some(httpClient))

  /** Sets or clears the HTTP client to send with. */
  def withHttpClient(httpClient: Option[Llm4sHttpClient]): MCPHttpOptions = copy(httpClient = httpClient)

  /**
   * The headers for one request: `headers`, then the bearer key, then `protocol` (the transport's own), each over
   * the one before. A key that cannot be read is the request's error.
   */
  private[mcp] def requestHeaders(protocol: Map[String, String]): Result[Map[String, String]] =
    apiKey
      .fold[Result[Option[String]]](Right(None))(_.current().map(key => Some(s"Bearer ${key.asKey}")))
      .map { bearer =>
        val withoutAuth =
          if (bearer.isDefined) headers.filterNot(_._1.equalsIgnoreCase("Authorization")) else headers
        val sent = withoutAuth ++ bearer.map("Authorization" -> _)
        // a transport header replaces a caller's header of the same name, whatever its case
        sent.filterNot { case (n, _) => protocol.keys.exists(_.equalsIgnoreCase(n)) } ++ protocol
      }

  override def toString: String =
    s"MCPHttpOptions(headers = ${headers.keys.toSeq.sorted.mkString("[", ", ", "]")}, " +
      s"apiKey = ${apiKey.fold("none")(_ => "***")}, httpClient = ${httpClient.fold("default")(_ => "supplied")})"
}

object MCPHttpOptions {

  /** No extra headers, no credential, and a client the transport creates. */
  val default: MCPHttpOptions = new MCPHttpOptions(Map.empty, None, None)

  /**
   * Creates an [[MCPHttpOptions]].
   *
   * @param headers    sent with every request
   * @param apiKey     sent as `Authorization: Bearer`
   * @param httpClient the client to send with; never closed by the transport
   */
  def apply(
    headers: Map[String, String] = Map.empty,
    apiKey: Option[ApiKeyProvider] = None,
    httpClient: Option[Llm4sHttpClient] = None
  ): MCPHttpOptions = new MCPHttpOptions(headers, apiKey, httpClient)
}

/**
 * HTTP+SSE transport configuration (MCP 2024-11-05).
 *
 * The client `GET`s `url`, which opens an event stream whose first `endpoint` event names where to POST messages;
 * replies arrive on the stream. `MCPClientImpl` still tries Streamable HTTP at `url` first.
 *
 * @param url  HTTP URL of the SSE endpoint (an llm4s `MCPServer`'s is `{path}/sse`)
 * @param name Unique identifier for this transport instance
 * @param http headers, credential and HTTP client (#3078)
 */
case class SSETransport(url: String, name: String, http: MCPHttpOptions = MCPHttpOptions.default) extends MCPTransport

/**
 * Streamable HTTP transport configuration (MCP 2025-03-26 and later).
 *
 * Connects to server via HTTP with support for streaming responses.
 * This is the modern transport protocol supporting single-endpoint design.
 *
 * @param url  HTTP URL of the MCP endpoint
 * @param name Unique identifier for this transport instance
 * @param http headers, credential and HTTP client (#3078)
 */
case class StreamableHTTPTransport(url: String, name: String, http: MCPHttpOptions = MCPHttpOptions.default)
    extends MCPTransport

/**
 * Base trait for transport implementations.
 *
 * Provides the interface for sending JSON-RPC requests and notifications
 * to an MCP server and managing the connection lifecycle.
 */
trait MCPTransportImpl {
  def name: String
  // Sends a JSON-RPC request and waits for response
  def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse]
  // Sends a JSON-RPC notification (no response expected)
  def sendNotification(notification: JsonRpcNotification): Result[Unit]
  // Closes the transport connection
  def close(): Unit

  /**
   * Records the protocol version the handshake agreed (#3079), which an HTTP transport sends as
   * `MCP-Protocol-Version` on every request after `initialize`. A no-op for a transport with no such header.
   */
  def useProtocolVersion(version: String): Unit = ()
}

/**
 * An HTTP status that refused a request (#3079): `MCPClientImpl` falls back to HTTP+SSE when a Streamable HTTP
 * server refuses `initialize` with a 4xx. `message` is what the transports always reported.
 */
final private[mcp] case class MCPHttpStatus(status: Int, message: String) extends LLMError

/**
 * A request the server provably did not run, because the session it belonged to is gone (#3072): an HTTP server
 * refused it with 404 for its session id, or a stdio server's process had died and the request was never written to
 * its replacement. `MCPClientImpl` answers it by running the handshake again and retrying the request once.
 */
final private[mcp] case class MCPSessionLost(message: String) extends LLMError

private[mcp] object MCPSessionLost {
  val Expired: MCPSessionLost = MCPSessionLost("Transport error: MCP session expired, client should reinitialize")
  val Restarted: MCPSessionLost =
    MCPSessionLost("Transport error: MCP server process restarted, client should reinitialize")
  val StreamLost: MCPSessionLost =
    MCPSessionLost("Transport error: MCP event stream was lost and reopened, client should reinitialize")
}

// Session management for Streamable HTTP
case class MCPSession(
  sessionId: String,
  lastEventId: Option[String] = None
)

private[mcp] object JsonRpcReplies {

  /**
   * Whether `json` is the JSON-RPC response to the request with id `requestId` ([[https://github.com/llm4s/llm4s/issues/1846 #1846]]):
   * an object with no `method` (a server-to-client request or notification has one) and the request's `id`.
   * upickle ignores unknown keys, so without this check a server request or a response to another id read as
   * this call's reply. A response with neither `result` nor `error` still matches, as it always has: it is
   * the server's reply to this call, if a malformed one.
   */
  def isResponseTo(requestId: String, json: ujson.Value): Boolean = json match {
    case obj: ujson.Obj =>
      val fields = obj.value
      !fields.contains("method") && fields.get("id").contains(ujson.Str(requestId))
    case _ => false
  }

  /**
   * A request the server sent the client (#3079): an object with both a `method` and an `id`. A notification has
   * no `id`, a response no `method`.
   */
  def serverRequest(json: ujson.Value): Option[(ujson.Value, String)] = json match {
    case obj: ujson.Obj =>
      for {
        method <- obj.value.get("method").flatMap(_.strOpt)
        id     <- obj.value.get("id").filter(id => id.strOpt.isDefined || id.numOpt.isDefined)
      } yield id -> method
    case _ => None
  }

  /**
   * The client's answer to a request the server sent it (#3079), `id` as the server sent it: an empty result for
   * `ping`, which the specification requires a receiver to answer promptly, and method-not-found (-32601) for
   * anything else. The client declares no capability (no `roots`, no `sampling`), so a server has no business
   * sending it any other request, and an answer is still owed, or the server waits for one that never comes.
   */
  def answerToServerRequest(id: ujson.Value, method: String): ujson.Obj =
    if (method == "ping") ujson.Obj("jsonrpc" -> "2.0", "id" -> id, "result" -> ujson.Obj())
    else
      ujson.Obj(
        "jsonrpc" -> "2.0",
        "id"      -> id,
        "error"   -> ujson.Obj("code" -> MethodNotFound, "message" -> "Method not found")
      )

  /** JSON-RPC's method-not-found code. */
  val MethodNotFound: Int = -32601

  /**
   * How every transport reports a JSON-RPC error: its code, its message and, when it has one, its `data` (#3079),
   * which is where a server puts the detail (the invalid parameter, the reason). The server's text is redacted and
   * the `data` cut to [[PayloadLog.MaxChars]].
   */
  def errorMessage(error: JsonRpcError): String = {
    val message = Redaction.safeBody(error.message)
    val data = error.data.filterNot(_ == ujson.Null).map { d =>
      val text = d.strOpt.getOrElse(ujson.write(d))
      s" (data: ${PayloadLog.preview(text)})"
    }
    s"JSON-RPC Error ${error.code}: $message${data.getOrElse("")}"
  }
}

// Factory for creating transport implementations
object MCPTransport {
  // Creates appropriate transport implementation based on configuration
  def create(config: MCPServerConfig): MCPTransportImpl =
    config.transport match {
      case stdio: StdioTransport      => StdioTransportImpl.of(stdio, config.timeout)
      case t: SSETransport            => new SSETransportImpl(t.url, t.name, config.timeout, t.http)
      case t: StreamableHTTPTransport => new StreamableHTTPTransportImpl(t.url, t.name, config.timeout, t.http)
    }
}

/** What the HTTP transports share: reading the outcome of an exchange. */
private[mcp] object HttpExchanges {

  extension [A](attempt: Try[Result[A]])
    /**
     * The exchange's result, with an exception thrown while making it as a failure too. A cancellation
     * stays a `CancelledError` (design section 4.4); any other failure is a `SimpleError` carrying the
     * message the transports always reported.
     */
    def flattened: Result[A] =
      attempt.toEither.left
        .map(e => CancelledError.fromThrowable(e, "mcp.http").getOrElse(SimpleError(e.getMessage)): LLMError)
        .flatMap(identity)
}

/**
 * How the transports log a JSON-RPC payload or a line from the server: secrets redacted and cut to [[MaxChars]].
 * A tool argument or result can be megabytes, and logging it whole floods the log - a single 1 MB line stalled CI's
 * log processing for half an hour.
 *
 * A payload longer than [[RedactWholeLimit]] is not redacted whole to show its first [[MaxChars]] characters:
 * redaction costs about a second per megabyte, and a transport logs while its exchange's deadline is running, so
 * one skipped 1 MB SSE event at DEBUG could time the request out. `Redaction.safeBody` previews such a payload from
 * a prefix of it, at a cost that no longer grows with the payload, and shows nothing of one whose prefix it cannot
 * redact safely.
 */
private[mcp] object PayloadLog {

  val MaxChars: Int = 2048

  /**
   * The longest payload redacted whole: `Redaction.safeBody`'s own limit, so every longer payload takes its
   * bounded path and a shorter one keeps the `[truncated, N chars omitted]` suffix.
   */
  val RedactWholeLimit: Int = 128 * 1024

  def preview(payload: String): String =
    if (payload.length <= RedactWholeLimit) Redaction.redactForLogging(payload, MaxChars)
    else Redaction.safeBody(payload, MaxChars)

  extension (logger: Logger)
    /** Logs `message` followed by a preview of `payload`, building the preview only when DEBUG is enabled. */
    def debugPayload(message: String, payload: String): Unit =
      if (logger.isDebugEnabled) logger.debug(message + preview(payload))
}
