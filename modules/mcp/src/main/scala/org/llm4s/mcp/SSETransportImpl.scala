package org.llm4s.mcp

import org.llm4s.error.{ CancelledError, SimpleError }
import org.llm4s.types.Result
import org.llm4s.util.Redaction
import scala.util.{ Try, Success, Failure }
import java.util.concurrent.atomic.AtomicLong
import upickle.default._
import org.slf4j.LoggerFactory
import org.llm4s.http.Llm4sHttpClient
import scala.concurrent.duration._
import HttpExchanges.flattened
import PayloadLog.debugPayload
// SSE transport implementation using HTTP (2024-11-05 spec)
class SSETransportImpl(
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
  private val protocolVersion              = "2024-11-05"

  logger.info(s"SSETransport($name) initialized for URL: $url with timeout: $timeout")

  // Sends JSON-RPC request via HTTP POST
  override def sendRequest(request: JsonRpcRequest): Result[JsonRpcResponse] = {
    logger.debug(s"SSETransport($name) sending request to $url: method=${request.method}, id=${request.id}")

    Try {
      val requestJson = write(request)
      logger.debugPayload(s"SSETransport($name) request JSON: ", requestJson)

      // Build headers according to MCP 2024-11-05 specification
      http
        .requestHeaders(buildHeaders(request))
        .flatMap(headers => httpClient.post(url = url, headers = headers, body = requestJson, timeout = timeout))
        .map { response =>
          logger.debug(s"SSETransport($name) received HTTP response: status=${response.statusCode}")
          response
        }
    }.flattened match {
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error) =>
        logger.error(s"SSETransport($name) transport error for $url: ${error.message}")
        Left(SimpleError(s"Transport error: ${error.message}"))
      case Right(response) =>
        // Handle session management during initialization
        if (request.method == "initialize" && response.statusCode >= 200 && response.statusCode < 300) {
          // Look for mcp-session-id header in response (lowercase per spec)
          val sessionIdOpt = response.headers.get("mcp-session-id").flatMap(_.headOption)

          sessionIdOpt.foreach { sessionId =>
            val trimmed = sessionId.trim
            if (trimmed.nonEmpty) {
              mcpSessionId = Some(trimmed)
              logger.info(s"SSETransport($name) established an MCP session")
            }
          }

          if (sessionIdOpt.isEmpty) {
            logger.debug(s"SSETransport($name) no session management (server chose not to use sessions)")
          }
        }

        // Handle session expiration (404 with existing session)
        if (response.statusCode == 404 && mcpSessionId.isDefined) {
          logger.warn(s"SSETransport($name) session expired (404), clearing session")
          mcpSessionId = None
          Left(MCPSessionLost.Expired)
        } else if (response.statusCode >= 400) {
          // Handle other HTTP errors
          Left(
            SimpleError(
              s"Transport error: HTTP error ${response.statusCode}: ${Redaction.safeBody(response.body)}"
            )
          )
        } else {
          // Determine response type based on content-type header
          val responseBody = response.body
          val contentType  = response.headers.get("content-type").flatMap(_.headOption).map(_.toLowerCase)
          val isSSE        = contentType.exists(_.contains("text/event-stream"))

          val jsonResponseResult = if (isSSE) {
            // Server responded with SSE stream
            logger.debug(s"SSETransport($name) received SSE stream response")
            parseSSEResponse(responseBody)
          } else {
            // Standard JSON response
            logger.debug(s"SSETransport($name) received JSON response")
            Try(read[JsonRpcResponse](responseBody)) match {
              case Success(r) => Right(r)
              case Failure(e) => Left(SimpleError(s"Transport error: ${e.getMessage}"))
            }
          }

          jsonResponseResult.flatMap { jsonResponse =>
            logger.debug(s"SSETransport($name) parsed JSON response: id=${jsonResponse.id}")
            jsonResponse.error match {
              case Some(error) =>
                val message = Redaction.safeBody(error.message)
                logger.error(s"SSETransport($name) JSON-RPC error from $url: code=${error.code}, message=$message")
                Left(SimpleError(s"JSON-RPC Error ${error.code}: $message"))
              case None =>
                logger.debug(s"SSETransport($name) request successful: id=${jsonResponse.id}")
                Right(jsonResponse)
            }
          }
        }
    }
  }

  /**
   * Build headers according to MCP 2024-11-05 specification.
   * Includes MCP-Protocol-Version header for non-initialize requests.
   */
  private def buildHeaders(request: JsonRpcRequest): Map[String, String] = {
    val baseHeaders = Map(
      "Content-Type" -> "application/json",
      "Accept"       -> "application/json, text/event-stream"
    )

    // Add MCP-Protocol-Version header for all requests except initialize
    val headersWithProtocol = if (request.method == "initialize") {
      baseHeaders // No protocol version header on initialize
    } else {
      baseHeaders + ("MCP-Protocol-Version" -> protocolVersion)
    }

    // Add session header if we have one (lowercase per spec)
    mcpSessionId.fold(headersWithProtocol)(sessionId => headersWithProtocol + ("mcp-session-id" -> sessionId))
  }

  private def parseSSEResponse(sseBody: String): Result[JsonRpcResponse] = {
    // Parse Server-Sent Events format according to MCP spec
    val lines = sseBody.split("\n")

    // Look for JSON-RPC responses in SSE data lines
    // According to spec: "The SSE stream SHOULD eventually include one JSON-RPC response per each JSON-RPC request"
    val jsonResponses = lines
      .filter(_.startsWith("data: "))
      .map(_.substring(6).trim)
      .filter(data => data.nonEmpty && data != "[DONE]")
      .flatMap { data =>
        Try(read[JsonRpcResponse](data)) match {
          case Success(response) => Some(response)
          case Failure(_)        =>
            // Skip non-JSON-RPC data (might be other SSE messages)
            logger.debugPayload(s"SSETransport($name) skipping non-JSON-RPC SSE data: ", data)
            None
        }
      }

    // Return the first valid JSON-RPC response
    jsonResponses.headOption match {
      case Some(response) => Right(response)
      case None           => Left(SimpleError("Transport error: No valid JSON-RPC response found in SSE stream"))
    }
  }

  override def sendNotification(notification: JsonRpcNotification): Result[Unit] = {
    logger.debug(s"SSETransport($name) sending notification to $url: method=${notification.method}")

    Try {
      val notificationJson = write(notification)
      logger.debugPayload(s"SSETransport($name) notification JSON: ", notificationJson)

      // Build headers for notification
      http
        .requestHeaders(buildNotificationHeaders())
        .flatMap(headers => httpClient.post(url = url, headers = headers, body = notificationJson, timeout = timeout))
        .map { response =>
          logger.debug(s"SSETransport($name) received HTTP response for notification: status=${response.statusCode}")
          response
        }
    }.flattened match {
      case Left(cancelled: CancelledError) => Left(cancelled)
      case Left(error) =>
        logger.error(s"SSETransport($name) notification error for $url: ${error.message}")
        Left(SimpleError(s"Notification error: ${error.message}"))
      case Right(response) =>
        // Handle HTTP errors
        if (response.statusCode >= 400) {
          val errorMsg =
            s"HTTP error ${response.statusCode}: ${Redaction.safeBody(response.body)}"
          logger.error(s"SSETransport($name) notification error for $url: $errorMsg")
          Left(SimpleError(s"Notification error: $errorMsg"))
        } else {
          // For notifications, we don't parse the response body since no response is expected
          logger.debug(s"SSETransport($name) notification sent successfully")
          logger.debug(s"SSETransport($name) notification successful")
          Right(())
        }
    }
  }

  /**
   * Build headers for notifications according to MCP 2024-11-05 specification.
   */
  private def buildNotificationHeaders(): Map[String, String] = {
    val baseHeaders = Map(
      "Content-Type" -> "application/json"
      // No Accept header since we don't expect a response
    )

    // Add MCP-Protocol-Version header (notifications still need protocol version)
    val headersWithProtocol = baseHeaders + ("MCP-Protocol-Version" -> protocolVersion)

    // Add session header if we have one
    mcpSessionId.fold(headersWithProtocol)(sessionId => headersWithProtocol + ("mcp-session-id" -> sessionId))
  }

  // Closes the HTTP client connection
  override def close(): Unit = {
    logger.info(s"SSETransport($name) closing connection to $url")

    // Send DELETE request to explicitly terminate session if we have one
    mcpSessionId.foreach { sessionId =>
      http
        .requestHeaders(Map("mcp-session-id" -> sessionId))
        .flatMap(headers => httpClient.delete(url = url, headers = headers, timeout = timeout)) match {
        case Right(_) =>
          logger.debug(s"SSETransport($name) sent session termination request")
        case Left(e) =>
          logger.debug(s"SSETransport($name) session termination failed (server may return 405): ${e.message}")
      }
    }

    // Clear session
    mcpSessionId = None
    if (ownsClient) httpClient.close()
    logger.debug(s"SSETransport($name) closed successfully")
  }

  // Generates unique request IDs
  def generateId(): String = requestId.incrementAndGet().toString
}
