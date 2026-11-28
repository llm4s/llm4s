// scalafix:off DisableSyntax.NoKeywordFinally
package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, LLMError, SimpleError }
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import scala.util.{ Try, Success, Failure }
import java.util.concurrent.atomic.AtomicLong
import upickle.default._
import org.slf4j.LoggerFactory
import org.llm4s.http.{ Llm4sHttpClient, StreamingHttpResponse }
import scala.concurrent.duration._
import HttpExchanges.flattened
import PayloadLog.debugPayload

/**
 * The Streamable HTTP transport (MCP 2025-03-26 and later): every message is a POST to one endpoint, answered with
 * JSON or an SSE stream.
 *
 * @param url     the MCP endpoint
 * @param name    the server's name, for logs and errors
 * @param timeout the longest one exchange may take, reply included
 * @param http    the headers and credential sent with every request, and the HTTP client (#3078); a client the
 *                transport creates is closed with it, one it was given never is
 */
class StreamableHTTPTransportImpl(
  url: String,
  override val name: String,
  timeout: FiniteDuration = 30.seconds,
  http: MCPHttpOptions = MCPHttpOptions.default
) extends MCPTransportImpl {

  /** A transport that sends with `httpClient`, which it never closes. */
  def this(url: String, name: String, timeout: FiniteDuration, httpClient: Llm4sHttpClient) =
    this(url, name, timeout, MCPHttpOptions.default.withHttpClient(httpClient))

  private val httpClient: Llm4sHttpClient  = http.httpClient.getOrElse(Llm4sHttpClient.create())
  private val ownsClient                   = http.httpClient.isEmpty
  private val logger                       = LoggerFactory.getLogger(getClass)
  private val requestId                    = new AtomicLong(0)
  private var mcpSessionId: Option[String] = None
  // The version the handshake agreed (#3079), sent as MCP-Protocol-Version on every request after initialize
  @volatile private var protocolVersion: Option[String] = None

  override def useProtocolVersion(version: String): Unit = protocolVersion = Some(version)

  logger.info(s"StreamableHTTPTransport($name) initialized for URL: $url with timeout: $timeout")

  /**
   * Sends `request` and reads its reply, all within [[timeout]] ([[https://github.com/llm4s/llm4s/issues/1785 #1785]]).
   *
   * The HTTP request timeout covers only the wait for response headers, and a server that answers with an
   * SSE stream sends them at once. The body is therefore read through a [[BoundedResponseBody]] whose deadline
   * is the same instant: whether the reply is plain JSON or an SSE stream, the whole exchange ends by then, with
   * a timed-out transport error, and the connection is closed. An SSE reply is read event by event and the
   * first JSON-RPC response in it is returned at once, without waiting for the server to end the stream.
   */
  override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
    logger.debug(s"StreamableHTTPTransport($name) sending request to $url: method=${request.method}, id=${request.id}")
    val deadline = Deadline.now + timeout

    Try {
      val requestJson = write(request)
      logger.debugPayload(s"StreamableHTTPTransport($name) request JSON: ", requestJson)

      // POST to MCP endpoint (single endpoint, no /sse suffix); the body is read below, against the deadline
      http
        .requestHeaders(buildHeaders(request))
        .flatMap(headers => httpClient.postStream(url = url, headers = headers, body = requestJson, timeout = timeout))
        .map { response =>
          logger.debug(s"StreamableHTTPTransport($name) received HTTP response: status=${response.statusCode}")
          response
        }
    }.flattened match {
      case Left(error) => Left(transportFailure(error))
      case Right(response) =>
        val body = new BoundedResponseBody(response.body, deadline, timeout, url)
        try handleResponse(request, response, body)
        finally body.close()
    }
  }

  /** A cancellation stays one; any other failure of the exchange is reported as a transport error. */
  private def transportFailure(error: LLMError): LLMError = error match {
    case cancelled: CancelledError => cancelled
    case other =>
      logger.error(s"StreamableHTTPTransport($name) transport error for $url: ${other.message}")
      SimpleError(s"Transport error: ${other.message}")
  }

  private def handleResponse(
    request: JsonRpcRequest,
    response: StreamingHttpResponse,
    body: BoundedResponseBody
  ): Result[JsonRpcResponse] = {
    // Handle session management during initialization according to MCP spec : the server may or may not include a session id
    if (request.method == "initialize" && response.statusCode >= 200 && response.statusCode < 300) {
      // Look for mcp-session-id header in response (lowercase per spec)
      val sessionIdOpt = response.headers.get("mcp-session-id").flatMap(_.headOption)

      sessionIdOpt.foreach { sessionId =>
        val trimmed = sessionId.trim
        if (trimmed.nonEmpty) {
          mcpSessionId = Some(trimmed)
          // The id is the session's credential with the server: it is never logged (#3078)
          logger.info(s"StreamableHTTPTransport($name) established an MCP session")
        }
      }

      if (sessionIdOpt.isEmpty) {
        logger.debug(s"StreamableHTTPTransport($name) no session management (server chose not to use sessions)")
      }
    }

    // Handle session expiration (404 with existing session)
    if (response.statusCode == 404 && mcpSessionId.isDefined) {
      logger.warn(s"StreamableHTTPTransport($name) session expired (404), clearing session")
      mcpSessionId = None
      Left(MCPSessionLost.Expired)
    } else if (response.statusCode == 405) {
      // Handle 405 Method Not Allowed (server doesn't support Streamable HTTP)
      Left(
        MCPHttpStatus(
          405,
          "Transport error: Server does not support Streamable HTTP transport (405 Method Not Allowed)"
        )
      )
    } else if (response.statusCode >= 400) {
      // Handle other HTTP errors
      body.readAll() match {
        case Left(error) => Left(transportFailure(error))
        case Right(text) =>
          Left(
            MCPHttpStatus(
              response.statusCode,
              s"Transport error: HTTP error ${response.statusCode}: ${Redaction.safeBody(text)}"
            )
          )
      }
    } else {
      // Determine response type based on content-type header
      val contentType = response.headers.get("content-type").flatMap(_.headOption).map(_.toLowerCase)
      val isSSE       = contentType.exists(_.contains("text/event-stream"))

      val jsonResponseResult = if (isSSE) {
        // Server chose to respond with SSE stream
        logger.debug(s"StreamableHTTPTransport($name) received SSE stream response")
        readSSEResponse(request, body)
      } else {
        // Standard JSON response
        logger.debug(s"StreamableHTTPTransport($name) received JSON response")
        body.readAll() match {
          case Left(error) => Left(transportFailure(error))
          case Right(text) =>
            Try(read[JsonRpcResponse](text)) match {
              case Success(r) => Right(r)
              case Failure(e) => Left(SimpleError(s"Transport error: ${e.getMessage}"))
            }
        }
      }

      jsonResponseResult.flatMap { jsonResponse =>
        logger.debug(s"StreamableHTTPTransport($name) parsed JSON response: id=${jsonResponse.id}")
        jsonResponse.error match {
          case Some(error) =>
            val message = JsonRpcReplies.errorMessage(error)
            logger.error(s"StreamableHTTPTransport($name) error from $url: $message")
            Left(SimpleError(message))
          case None =>
            logger.debug(s"StreamableHTTPTransport($name) request successful: id=${jsonResponse.id}")
            Right(jsonResponse)
        }
      }
    }
  }

  /**
   * Build headers according to MCP 2025-06-18 specification.
   * Includes MCP-Protocol-Version header for non-initialize requests.
   */
  private def buildHeaders(request: JsonRpcRequest): Map[String, String] = {
    val baseHeaders = Map(
      "Content-Type" -> "application/json",
      "Accept"       -> "application/json, text/event-stream"
    )

    // The agreed version on every request except initialize, which is where it is agreed (#3079)
    val headersWithProtocol =
      if (request.method == "initialize") baseHeaders
      else protocolVersion.fold(baseHeaders)(v => baseHeaders + ("MCP-Protocol-Version" -> v))

    // Add session header if we have one (lowercase per spec)
    mcpSessionId.fold(headersWithProtocol)(sessionId => headersWithProtocol + ("mcp-session-id" -> sessionId))
  }

  /**
   * Reads an SSE reply event by event (W3C Server-Sent Events: `event:`, `data:`, `id:` and `retry:` lines,
   * an empty line ending an event, `:` starting a comment) and returns the first event whose data is the
   * JSON-RPC response to `request`, as soon as it has arrived. Other events - progress notifications,
   * server-to-client requests, a response to another id, `[DONE]` - are skipped
   * ([[https://github.com/llm4s/llm4s/issues/1846 #1846]]). A stream that ends without the response is an
   * error; one that outlasts the deadline is a timed-out transport error, and an event larger than the body's
   * size bound an error, both from [[BoundedResponseBody]].
   */
  private def readSSEResponse(request: JsonRpcRequest, body: BoundedResponseBody): Result[JsonRpcResponse] = {
    final case class SSEEvent(
      eventType: Option[String] = None,
      data: StringBuilder = new StringBuilder(),
      id: Option[String] = None
    ) {
      def nonEmpty: Boolean = data.nonEmpty || eventType.isDefined || id.isDefined
    }

    def jsonRpcResponse(event: SSEEvent): Option[JsonRpcResponse] = {
      val dataContent = event.data.toString
      if (dataContent.nonEmpty && dataContent != "[DONE]") {
        val parsed = Try(ujson.read(dataContent)).toOption
        // A request the server sends while it works on this one is answered at once (#3079); it is not the reply
        parsed.flatMap(JsonRpcReplies.serverRequest).foreach { case (id, method) => answerServer(id, method) }
        parsed.filter(JsonRpcReplies.isResponseTo(request.id, _)) match {
          case None =>
            logger.debugPayload(
              s"StreamableHTTPTransport($name) skipping non-JSON-RPC SSE data, or a message other than the response to id ${request.id}: ",
              dataContent
            )
            None
          case Some(json) =>
            jsonRpcResponseFrom(json, event)
        }
      } else {
        None
      }
    }

    def jsonRpcResponseFrom(json: ujson.Value, event: SSEEvent): Option[JsonRpcResponse] =
      Try(read[JsonRpcResponse](json)) match {
        case Success(response) =>
          logger.debug(
            s"StreamableHTTPTransport($name) found JSON-RPC response in SSE event: ${event.eventType.getOrElse("unnamed")}"
          )
          Some(response)
        case Failure(e) =>
          // Shaped like the response but not readable as one, e.g. a malformed error object
          logger.debugPayload(
            s"StreamableHTTPTransport($name) skipping unreadable JSON-RPC response (${e.getMessage}): ",
            event.data.toString
          )
          None
      }

    def noResponse(events: Int): Result[JsonRpcResponse] =
      Left(
        SimpleError(
          s"Transport error: No valid JSON-RPC response found in SSE stream. Found $events events, none contained valid JSON-RPC responses."
        )
      )

    @scala.annotation.tailrec
    def loop(current: SSEEvent, events: Int): Result[JsonRpcResponse] =
      body.readLine() match {
        case Left(error) => Left(transportFailure(error))
        case Right(None) =>
          // The stream ended; an event without a trailing empty line still counts
          if (current.nonEmpty) jsonRpcResponse(current).fold(noResponse(events + 1))(Right(_))
          else noResponse(events)
        case Right(Some(line)) =>
          val trimmedLine = line.trim
          if (trimmedLine.isEmpty) {
            // Empty line signals end of event
            if (current.nonEmpty) {
              jsonRpcResponse(current) match {
                case Some(response) => Right(response)
                case None           => loop(SSEEvent(), events + 1)
              }
            } else loop(current, events)
          } else if (trimmedLine.startsWith("data:")) {
            val data = if (trimmedLine.length > 5) trimmedLine.substring(5).trim else ""
            if (current.data.length + data.length + 1 > BoundedResponseBody.MaxReplyChars)
              Left(transportFailure(body.tooLarge()))
            else {
              if (current.data.nonEmpty) current.data.append("\n")
              current.data.append(data)
              loop(current, events)
            }
          } else if (trimmedLine.startsWith("event:")) {
            val eventType = if (trimmedLine.length > 6) trimmedLine.substring(6).trim else ""
            loop(current.copy(eventType = Some(eventType)), events)
          } else if (trimmedLine.startsWith("id:")) {
            val id = if (trimmedLine.length > 3) trimmedLine.substring(3).trim else ""
            loop(current.copy(id = Some(id)), events)
          } else if (trimmedLine.startsWith("retry:")) {
            // Retry line - we ignore this for now
            logger.debug(s"StreamableHTTPTransport($name) ignoring SSE retry directive: $trimmedLine")
            loop(current, events)
          } else {
            // Lines starting with : are comments; others that don't match the format are ignored
            if (!trimmedLine.startsWith(":"))
              logger.debugPayload(s"StreamableHTTPTransport($name) ignoring unrecognized SSE line: ", trimmedLine)
            loop(current, events)
          }
      }

    loop(SSEEvent(), 0)
  }

  /**
   * Answers a request the server sent in a reply stream (#3079): `ping` with an empty result, anything else with
   * method-not-found. Posted like a notification; a failure is logged, since the reply being read is unaffected.
   */
  private def answerServer(id: ujson.Value, method: String): Unit = {
    logger.debug(s"StreamableHTTPTransport($name) answering the server's request $method")
    postMessage(JsonRpcReplies.answerToServerRequest(id, method).render(), "answer").left.foreach { error =>
      logger.warn(s"StreamableHTTPTransport($name) could not answer the server's $method: ${error.message}")
    }
  }

  override def sendNotification(notification: JsonRpcNotification): Result[Unit] = {
    logger.debug(s"StreamableHTTPTransport($name) sending notification to $url: method=${notification.method}")
    postMessage(write(notification), "notification")
  }

  /** POSTs a message that gets no JSON-RPC reply: a notification, or an answer to a server's request. */
  private def postMessage(json: String, kind: String): Result[Unit] =
    Try {
      val notificationJson = json
      logger.debugPayload(s"StreamableHTTPTransport($name) $kind JSON: ", notificationJson)

      http
        .requestHeaders(buildNotificationHeaders())
        .flatMap(headers => httpClient.post(url = url, headers = headers, body = notificationJson, timeout = timeout))
        .map { response =>
          logger.debug(
            s"StreamableHTTPTransport($name) received HTTP response for notification: status=${response.statusCode}"
          )

          response
        }
    }.flattened match {
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error) =>
        logger.error(s"StreamableHTTPTransport($name) notification error for $url: ${error.message}")
        Left(SimpleError(s"Notification error: ${error.message}"))
      case Right(response) =>
        // Handle HTTP errors (notifications still use HTTP)
        if (response.statusCode >= 400) {
          val errorMsg =
            s"HTTP error ${response.statusCode}: ${Redaction.safeBody(response.body)}"
          logger.error(s"StreamableHTTPTransport($name) notification error for $url: $errorMsg")
          Left(SimpleError(s"Notification error: $errorMsg"))
        } else {
          // For notifications, we don't parse the response body since no response is expected
          logger.debug(s"StreamableHTTPTransport($name) notification sent successfully")
          logger.debug(s"StreamableHTTPTransport($name) notification successful")
          Right(())
        }
    }

  /**
   * Build headers for notifications. A notification gets no JSON-RPC response, but the Streamable HTTP
   * transport requires every POST to carry an `Accept` header listing both `application/json` and
   * `text/event-stream`; servers built on the MCP SDKs reject one without it with `406 Not Acceptable`.
   */
  private def buildNotificationHeaders(): Map[String, String] = {
    val baseHeaders = Map(
      "Content-Type" -> "application/json",
      "Accept"       -> "application/json, text/event-stream"
    )

    // The agreed version (#3079); notifications/initialized is sent once it is known
    val headersWithProtocol = protocolVersion.fold(baseHeaders)(v => baseHeaders + ("MCP-Protocol-Version" -> v))

    // Add session header if we have one
    mcpSessionId.fold(headersWithProtocol)(sessionId => headersWithProtocol + ("mcp-session-id" -> sessionId))
  }

  override def close(): Unit = {
    logger.info(s"StreamableHTTPTransport($name) closing connection to $url")

    // Send DELETE request to explicitly terminate session if we have one
    mcpSessionId.foreach { sessionId =>
      http
        .requestHeaders(Map("mcp-session-id" -> sessionId)) // lowercase per spec
        .flatMap(headers => httpClient.delete(url = url, headers = headers, timeout = timeout)) match {
        case Right(_) =>
          logger.debug(s"StreamableHTTPTransport($name) sent session termination request")
        case Left(e) =>
          logger.debug(
            s"StreamableHTTPTransport($name) session termination failed (server may return 405): ${e.message}"
          )
      }
    }

    // Clear session
    mcpSessionId = None
    // A client this transport created is its own to close; one it was given is the caller's (#3078)
    if (ownsClient) httpClient.close()
    logger.debug(s"StreamableHTTPTransport($name) closed successfully")
  }

  def generateId(): String = requestId.incrementAndGet().toString
}
