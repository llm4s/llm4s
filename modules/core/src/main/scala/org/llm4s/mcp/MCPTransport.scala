package org.llm4s.mcp

import scala.util.{ Try, Success, Failure }
import java.util.concurrent.atomic.AtomicLong
import upickle.default._
import org.slf4j.LoggerFactory
import scala.concurrent.duration._

// Transport type definitions

// Base trait for MCP transport mechanisms
sealed trait MCPTransport {
  def name: String
}

// Stdio transport using subprocess communication
case class StdioTransport(command: Seq[String], name: String) extends MCPTransport

// Server-Sent Events transport using HTTP (2024-11-05 spec)
case class SSETransport(url: String, name: String) extends MCPTransport

// Streamable HTTP transport using single endpoint (2025-03-26 spec)
case class StreamableHTTPTransport(url: String, name: String) extends MCPTransport

// Base trait for transport implementations
trait MCPTransportImpl {
  def name: String
  // Sends a JSON-RPC request and waits for response
  def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse]
  // Sends a JSON-RPC notification (no response expected)
  def sendNotification(notification: JsonRpcNotification): Either[String, Unit]
  // Closes the transport connection
  def close(): Unit
}

// Session management for Streamable HTTP
case class MCPSession(
  sessionId: String,
  lastEventId: Option[String] = None
)

// Streamable HTTP transport implementation (2025-06-18 spec)
class StreamableHTTPTransportImpl(url: String, override val name: String, timeout: Duration = 30.seconds)
    extends MCPTransportImpl {
  private val logger                       = LoggerFactory.getLogger(getClass)
  private val requestId                    = new AtomicLong(0)
  private var mcpSessionId: Option[String] = None

  logger.info(s"StreamableHTTPTransport($name) initialized for URL: $url with timeout: $timeout")

  override def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse] = {
    logger.debug(s"StreamableHTTPTransport($name) sending request to $url: method=${request.method}, id=${request.id}")

    Try {
      val requestJson = write(request)
      logger.debug(s"StreamableHTTPTransport($name) request JSON: $requestJson")

      // Build headers according to 2025-06-18 spec
      val headers = buildHeaders(request)

      // POST to MCP endpoint (single endpoint, no /sse suffix)
      val response = requests.post(
        url,
        data = requestJson,
        headers = headers,
        readTimeout = timeout.toMillis.toInt,
        connectTimeout = timeout.toMillis.toInt
      )

      logger.debug(s"StreamableHTTPTransport($name) received HTTP response: status=${response.statusCode}")

      // Handle session management during initialization according to MCP spec : the server may or may not include a session id
      if (request.method == "initialize" && response.statusCode >= 200 && response.statusCode < 300) {
        // Look for mcp-session-id header in response (lowercase per spec)
        val sessionIdOpt = response.headers.get("mcp-session-id")

        sessionIdOpt.foreach { sessionIdValue =>
          // Handle both String and Seq[String] types from requests library
          val sessionId = sessionIdValue match {
            case seq: Seq[_] if seq.nonEmpty => seq.head.toString.trim
            case other                       => other.toString.trim
          }
          if (sessionId.nonEmpty && !sessionId.startsWith("List(")) {
            mcpSessionId = Some(sessionId)
            logger.info(s"StreamableHTTPTransport($name) established MCP session: $sessionId")
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
        throw new RuntimeException("MCP session expired, client should reinitialize")
      }

      // Handle 405 Method Not Allowed (server doesn't support Streamable HTTP)
      if (response.statusCode == 405) {
        throw new RuntimeException("Server does not support Streamable HTTP transport (405 Method Not Allowed)")
      }

      // Handle other HTTP errors
      if (response.statusCode >= 400) {
        val errorBody = Try(response.text()).getOrElse("Unknown error")
        throw new RuntimeException(s"HTTP error ${response.statusCode}: $errorBody")
      }

      // Determine response type based on content-type header
      val responseBody = response.text()
      val contentType  = response.headers.get("content-type").map(_.toString.toLowerCase)
      val isSSE        = contentType.exists(_.contains("text/event-stream"))

      val jsonResponse = if (isSSE) {
        // Server chose to respond with SSE stream
        logger.debug(s"StreamableHTTPTransport($name) received SSE stream response")
        parseSSEResponse(responseBody)
      } else {
        // Standard JSON response
        logger.debug(s"StreamableHTTPTransport($name) received JSON response")
        read[JsonRpcResponse](responseBody)
      }

      logger.debug(s"StreamableHTTPTransport($name) parsed JSON response: id=${jsonResponse.id}")
      jsonResponse
    } match {
      case Success(response) =>
        response.error match {
          case Some(error) =>
            logger.error(
              s"StreamableHTTPTransport($name) JSON-RPC error from $url: code=${error.code}, message=${error.message}"
            )
            Left(s"JSON-RPC Error ${error.code}: ${error.message}")
          case None =>
            logger.debug(s"StreamableHTTPTransport($name) request successful: id=${response.id}")
            Right(response)
        }
      case Failure(exception) =>
        logger.error(s"StreamableHTTPTransport($name) transport error for $url: ${exception.getMessage}", exception)
        Left(s"Transport error: ${exception.getMessage}")
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

    // Add MCP-Protocol-Version header for all requests except initialize
    val headersWithProtocol = if (request.method == "initialize") {
      baseHeaders // No protocol version header on initialize
    } else {
      baseHeaders + ("MCP-Protocol-Version" -> "2025-06-18")
    }

    // Add session header if we have one (lowercase per spec)
    mcpSessionId.fold(headersWithProtocol)(sessionId => headersWithProtocol + ("mcp-session-id" -> sessionId))
  }

  private def parseSSEResponse(sseBody: String): JsonRpcResponse = {
    // Parse Server-Sent Events format according to W3C SSE specification
    // SSE format: event: <type>\ndata: <content>\nid: <id>\n\n
    val lines = sseBody.split("\n")

    case class SSEEvent(
      eventType: Option[String] = None,
      data: StringBuilder = new StringBuilder(),
      id: Option[String] = None
    )

    val events       = scala.collection.mutable.ListBuffer[SSEEvent]()
    var currentEvent = SSEEvent()

    // Parse SSE events according to the specification
    for (line <- lines) {
      val trimmedLine = line.trim

      if (trimmedLine.isEmpty) {
        // Empty line signals end of event
        if (currentEvent.data.nonEmpty || currentEvent.eventType.isDefined || currentEvent.id.isDefined) {
          events += currentEvent
          currentEvent = SSEEvent()
        }
      } else if (trimmedLine.startsWith("data:")) {
        // Data line
        val data = if (trimmedLine.length > 5) trimmedLine.substring(5).trim else ""
        if (currentEvent.data.nonEmpty) {
          currentEvent.data.append("\n")
        }
        currentEvent.data.append(data)
      } else if (trimmedLine.startsWith("event:")) {
        // Event type line
        val eventType = if (trimmedLine.length > 6) trimmedLine.substring(6).trim else ""
        currentEvent = currentEvent.copy(eventType = Some(eventType))
      } else if (trimmedLine.startsWith("id:")) {
        // Event ID line
        val id = if (trimmedLine.length > 3) trimmedLine.substring(3).trim else ""
        currentEvent = currentEvent.copy(id = Some(id))
      } else if (trimmedLine.startsWith("retry:")) {
        // Retry line - we ignore this for now
        logger.debug(s"StreamableHTTPTransport($name) ignoring SSE retry directive: $trimmedLine")
      } else if (!trimmedLine.startsWith(":")) {
        // Lines starting with : are comments, ignore others that don't match format
        logger.debug(s"StreamableHTTPTransport($name) ignoring unrecognized SSE line: $trimmedLine")
      }
    }

    // Add final event if there wasn't a trailing empty line
    if (currentEvent.data.nonEmpty || currentEvent.eventType.isDefined || currentEvent.id.isDefined) {
      events += currentEvent
    }

    // Look for JSON-RPC responses in the parsed events
    val jsonResponses = events.flatMap { event =>
      val dataContent = event.data.toString
      if (dataContent.nonEmpty && dataContent != "[DONE]") {
        Try(read[JsonRpcResponse](dataContent)) match {
          case Success(response) =>
            logger.debug(
              s"StreamableHTTPTransport($name) found JSON-RPC response in SSE event: ${event.eventType.getOrElse("unnamed")}"
            )
            Some(response)
          case Failure(e) =>
            // Skip non-JSON-RPC data (might be other SSE messages)
            logger.debug(
              s"StreamableHTTPTransport($name) skipping non-JSON-RPC SSE data: $dataContent (${e.getMessage})"
            )
            None
        }
      } else {
        None
      }
    }

    // Return the first valid JSON-RPC response
    jsonResponses.headOption.getOrElse {
      throw new RuntimeException(
        s"No valid JSON-RPC response found in SSE stream. Found ${events.size} events, none contained valid JSON-RPC responses."
      )
    }
  }

  override def sendNotification(notification: JsonRpcNotification): Either[String, Unit] = {
    logger.debug(s"StreamableHTTPTransport($name) sending notification to $url: method=${notification.method}")

    Try {
      val notificationJson = write(notification)
      logger.debug(s"StreamableHTTPTransport($name) notification JSON: $notificationJson")

      // Build headers for notification (same as requests)
      val headers = buildNotificationHeaders()

      // POST to MCP endpoint
      val response = requests.post(
        url,
        data = notificationJson,
        headers = headers,
        readTimeout = timeout.toMillis.toInt,
        connectTimeout = timeout.toMillis.toInt
      )

      logger.debug(
        s"StreamableHTTPTransport($name) received HTTP response for notification: status=${response.statusCode}"
      )

      // Handle HTTP errors (notifications still use HTTP)
      if (response.statusCode >= 400) {
        val errorBody = Try(response.text()).getOrElse("Unknown error")
        throw new RuntimeException(s"HTTP error ${response.statusCode}: $errorBody")
      }

      // For notifications, we don't parse the response body since no response is expected
      logger.debug(s"StreamableHTTPTransport($name) notification sent successfully")
      ()
    } match {
      case Success(_) =>
        logger.debug(s"StreamableHTTPTransport($name) notification successful")
        Right(())
      case Failure(exception) =>
        logger.error(s"StreamableHTTPTransport($name) notification error for $url: ${exception.getMessage}", exception)
        Left(s"Notification error: ${exception.getMessage}")
    }
  }

  /**
   * Build headers for notifications (similar to requests but notifications don't expect responses).
   */
  private def buildNotificationHeaders(): Map[String, String] = {
    val baseHeaders = Map(
      "Content-Type" -> "application/json"
      // No Accept header since we don't expect a response
    )

    // Add MCP-Protocol-Version header (notifications still need protocol version)
    val headersWithProtocol = baseHeaders + ("MCP-Protocol-Version" -> "2025-06-18")

    // Add session header if we have one
    mcpSessionId.fold(headersWithProtocol)(sessionId => headersWithProtocol + ("mcp-session-id" -> sessionId))
  }

  override def close(): Unit = {
    logger.info(s"StreamableHTTPTransport($name) closing connection to $url")

    // Send DELETE request to explicitly terminate session if we have one
    mcpSessionId.foreach { sessionId =>
      Try {
        requests.delete(
          url,
          headers = Map("mcp-session-id" -> sessionId), // lowercase per spec
          readTimeout = timeout.toMillis.toInt,
          connectTimeout = timeout.toMillis.toInt
        )
        logger.debug(s"StreamableHTTPTransport($name) sent session termination request")
      }.recover { case e =>
        logger.debug(
          s"StreamableHTTPTransport($name) session termination failed (server may return 405): ${e.getMessage}"
        )
      }
    }

    // Clear session
    mcpSessionId = None
    logger.debug(s"StreamableHTTPTransport($name) closed successfully")
  }

  def generateId(): String = requestId.incrementAndGet().toString
}

// SSE transport implementation using HTTP (2024-11-05 spec)
class SSETransportImpl(url: String, override val name: String, timeout: Duration = 30.seconds)
    extends MCPTransportImpl {
  private val logger                       = LoggerFactory.getLogger(getClass)
  private val requestId                    = new AtomicLong(0)
  private var mcpSessionId: Option[String] = None
  private val protocolVersion              = "2024-11-05"

  logger.info(s"SSETransport($name) initialized for URL: $url with timeout: $timeout")

  // Sends JSON-RPC request via HTTP POST
  override def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse] = {
    logger.debug(s"SSETransport($name) sending request to $url: method=${request.method}, id=${request.id}")

    Try {
      val requestJson = write(request)
      logger.debug(s"SSETransport($name) request JSON: $requestJson")

      // Build headers according to MCP 2024-11-05 specification
      val headers = buildHeaders(request)

      val response = requests.post(
        url, // Remove /sse suffix - MCP servers use base URL
        data = requestJson,
        headers = headers,
        readTimeout = timeout.toMillis.toInt,
        connectTimeout = timeout.toMillis.toInt
      )

      logger.debug(s"SSETransport($name) received HTTP response: status=${response.statusCode}")

      // Handle session management during initialization
      if (request.method == "initialize" && response.statusCode >= 200 && response.statusCode < 300) {
        // Look for mcp-session-id header in response (lowercase per spec)
        val sessionIdOpt = response.headers.get("mcp-session-id")

        sessionIdOpt.foreach { sessionIdValue =>
          // Handle both String and Seq[String] types from requests library
          val sessionId = sessionIdValue match {
            case seq: Seq[_] if seq.nonEmpty => seq.head.toString.trim
            case other                       => other.toString.trim
          }
          if (sessionId.nonEmpty && !sessionId.startsWith("List(")) {
            mcpSessionId = Some(sessionId)
            logger.info(s"SSETransport($name) established MCP session: $sessionId")
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
        throw new RuntimeException("MCP session expired, client should reinitialize")
      }

      // Handle other HTTP errors
      if (response.statusCode >= 400) {
        val errorBody = Try(response.text()).getOrElse("Unknown error")
        throw new RuntimeException(s"HTTP error ${response.statusCode}: $errorBody")
      }

      // Determine response type based on content-type header
      val responseBody = response.text()
      val contentType  = response.headers.get("content-type").map(_.toString.toLowerCase)
      val isSSE        = contentType.exists(_.contains("text/event-stream"))

      val jsonResponse = if (isSSE) {
        // Server responded with SSE stream
        logger.debug(s"SSETransport($name) received SSE stream response")
        parseSSEResponse(responseBody)
      } else {
        // Standard JSON response
        logger.debug(s"SSETransport($name) received JSON response")
        read[JsonRpcResponse](responseBody)
      }

      logger.debug(s"SSETransport($name) parsed JSON response: id=${jsonResponse.id}")
      jsonResponse
    } match {
      case Success(response) =>
        response.error match {
          case Some(error) =>
            logger.error(s"SSETransport($name) JSON-RPC error from $url: code=${error.code}, message=${error.message}")
            Left(s"JSON-RPC Error ${error.code}: ${error.message}")
          case None =>
            logger.debug(s"SSETransport($name) request successful: id=${response.id}")
            Right(response)
        }
      case Failure(exception) =>
        logger.error(s"SSETransport($name) transport error for $url: ${exception.getMessage}", exception)
        Left(s"Transport error: ${exception.getMessage}")
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

  private def parseSSEResponse(sseBody: String): JsonRpcResponse = {
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
            logger.debug(s"SSETransport($name) skipping non-JSON-RPC SSE data: $data")
            None
        }
      }

    // Return the first valid JSON-RPC response
    jsonResponses.headOption.getOrElse {
      throw new RuntimeException("No valid JSON-RPC response found in SSE stream")
    }
  }

  override def sendNotification(notification: JsonRpcNotification): Either[String, Unit] = {
    logger.debug(s"SSETransport($name) sending notification to $url: method=${notification.method}")

    Try {
      val notificationJson = write(notification)
      logger.debug(s"SSETransport($name) notification JSON: $notificationJson")

      // Build headers for notification
      val headers = buildNotificationHeaders()

      val response = requests.post(
        url,
        data = notificationJson,
        headers = headers,
        readTimeout = timeout.toMillis.toInt,
        connectTimeout = timeout.toMillis.toInt
      )

      logger.debug(s"SSETransport($name) received HTTP response for notification: status=${response.statusCode}")

      // Handle HTTP errors
      if (response.statusCode >= 400) {
        val errorBody = Try(response.text()).getOrElse("Unknown error")
        throw new RuntimeException(s"HTTP error ${response.statusCode}: $errorBody")
      }

      // For notifications, we don't parse the response body since no response is expected
      logger.debug(s"SSETransport($name) notification sent successfully")
      ()
    } match {
      case Success(_) =>
        logger.debug(s"SSETransport($name) notification successful")
        Right(())
      case Failure(exception) =>
        logger.error(s"SSETransport($name) notification error for $url: ${exception.getMessage}", exception)
        Left(s"Notification error: ${exception.getMessage}")
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
      Try {
        requests.delete(
          url,
          headers = Map("mcp-session-id" -> sessionId), // lowercase per spec
          readTimeout = timeout.toMillis.toInt,
          connectTimeout = timeout.toMillis.toInt
        )
        logger.debug(s"SSETransport($name) sent session termination request")
      }.recover { case e =>
        logger.debug(
          s"SSETransport($name) session termination failed (server may return 405): ${e.getMessage}"
        )
      }
    }

    // Clear session
    mcpSessionId = None
    logger.debug(s"SSETransport($name) closed successfully")
  }

  // Generates unique request IDs
  def generateId(): String = requestId.incrementAndGet().toString
}

// Stdio transport implementation using subprocess communication with proper MCP protocol compliance
class StdioTransportImpl(command: Seq[String], override val name: String) extends MCPTransportImpl {
  private val logger                                       = LoggerFactory.getLogger(getClass)
  private var process: Option[Process]                     = None
  private val requestId                                    = new AtomicLong(0)
  private var stdinWriter: Option[java.io.PrintWriter]     = None
  private var stdoutReader: Option[java.io.BufferedReader] = None
  private var stderrReader: Option[java.io.BufferedReader] = None

  // Timeout for server responses (30 seconds)
  private val RESPONSE_TIMEOUT_MS = 30000
  // Timeout for server startup (10 seconds)
  private val STARTUP_TIMEOUT_MS = 10000

  logger.info(s"StdioTransport($name) initialized with command: ${command.mkString(" ")}")

  // Gets existing process or starts new one if needed
  private def getOrStartProcess(): Either[String, Process] =
    process match {
      case Some(p) if p.isAlive =>
        logger.debug(s"StdioTransport($name) reusing existing process")
        Right(p)
      case _ =>
        startNewProcess()
    }

  // Starts a new MCP server process with proper initialization
  private def startNewProcess(): Either[String, Process] = {
    logger.info(s"StdioTransport($name) starting new process: ${command.mkString(" ")}")

    Try {
      val processBuilder = new ProcessBuilder(command: _*)
      processBuilder.redirectErrorStream(false) // Keep stderr separate for monitoring
      val newProcess = processBuilder.start()

      // Set up I/O streams
      stdinWriter = Some(
        new java.io.PrintWriter(
          new java.io.OutputStreamWriter(newProcess.getOutputStream, "UTF-8"),
          true // auto-flush
        )
      )
      stdoutReader = Some(
        new java.io.BufferedReader(
          new java.io.InputStreamReader(newProcess.getInputStream, "UTF-8")
        )
      )
      stderrReader = Some(
        new java.io.BufferedReader(
          new java.io.InputStreamReader(newProcess.getErrorStream, "UTF-8")
        )
      )

      process = Some(newProcess)

      // Wait for server to be ready (check if it's responsive)
      waitForServerReady(newProcess) match {
        case Right(_) =>
          logger.info(s"StdioTransport($name) process started and ready")
          // Process started successfully
          newProcess
        case Left(error) =>
          // Clean up failed process
          cleanupProcess()
          throw new RuntimeException(s"Server startup failed: $error")
      }
    } match {
      case Success(p) => Right(p)
      case Failure(e) =>
        cleanupProcess()
        logger.error(s"StdioTransport($name) failed to start process: ${e.getMessage}", e)
        Left(s"Failed to start MCP server process: ${e.getMessage}")
    }
  }

  // Wait for the server to be ready to accept requests
  private def waitForServerReady(proc: Process): Either[String, Unit] = {
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < STARTUP_TIMEOUT_MS) {
      if (!proc.isAlive) {
        // Check stderr for error messages
        val errorOutput = readAvailableStderr()
        return Left(s"Process died during startup. Error output: $errorOutput")
      }

      // Check if there's any output indicating the server is ready
      if (stdoutReader.exists(_.ready()) || stderrReader.exists(_.ready())) {
        logger.debug(s"StdioTransport($name) server appears ready (has output)")
        return Right(())
      }

      Thread.sleep(100) // Small delay before checking again
    }

    // Server might be ready even without immediate output
    if (proc.isAlive) {
      logger.debug(s"StdioTransport($name) server process is alive, assuming ready")
      Right(())
    } else {
      Left("Server process died during startup")
    }
  }

  // Read any available stderr output for diagnostics
  private def readAvailableStderr(): String =
    stderrReader match {
      case Some(reader) =>
        val output = new StringBuilder
        scala.util
          .Try {
            while (reader.ready()) {
              val line = reader.readLine()
              if (line != null) {
                output.append(line).append("\n")
                logger.info(s"StdioTransport($name) stderr: $line")
              }
            }
          }
          .fold(e => logger.debug(s"Error reading stderr: ${e.getMessage}"), _ => ())
        output.toString
      case None => ""
    }

  // Sends JSON-RPC request via subprocess stdin/stdout with proper MCP protocol
  override def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse] = {
    logger.debug(s"StdioTransport($name) sending request: method=${request.method}, id=${request.id}")

    getOrStartProcess().flatMap { _ =>
      (stdinWriter, stdoutReader) match {
        case (Some(writer), Some(reader)) =>
          Try {
            // Read any pending stderr for diagnostics
            readAvailableStderr()

            val requestJson = write(request)
            logger.info(s"StdioTransport($name) writing to stdin: $requestJson")

            // Write request as line-delimited JSON (one complete JSON object per line)
            writer.println(requestJson)
            writer.flush() // Ensure the request is immediately sent to the server

            if (writer.checkError()) {
              throw new RuntimeException("Failed to write to process stdin (broken pipe)")
            }

            // Read response with timeout
            val responseLine = readResponseWithTimeout(reader, request.id)

            if (responseLine.isEmpty) {
              throw new RuntimeException(s"No response from MCP server for request ${request.id}")
            }

            logger.info(s"StdioTransport($name) received from stdout: $responseLine")

            // Parse JSON response
            val response = read[JsonRpcResponse](responseLine)

            // Validate response ID matches request ID
            if (response.id != request.id) {
              logger.warn(s"Response ID ${response.id} doesn't match request ID ${request.id}")
            }

            response
          } match {
            case Success(response) =>
              response.error match {
                case Some(error) =>
                  logger.error(s"StdioTransport($name) JSON-RPC error: code=${error.code}, message=${error.message}")
                  Left(s"JSON-RPC Error ${error.code}: ${error.message}")
                case None =>
                  logger.debug(s"StdioTransport($name) request successful: id=${response.id}")
                  Right(response)
              }
            case Failure(exception) =>
              // Read stderr for additional context
              val stderrOutput = readAvailableStderr()
              val errorMsg = if (stderrOutput.nonEmpty) {
                s"${exception.getMessage}. Server stderr: $stderrOutput"
              } else {
                exception.getMessage
              }

              logger.error(s"StdioTransport($name) transport error: $errorMsg", exception)
              Left(s"Stdio transport error: $errorMsg")
          }
        case _ =>
          Left("Process I/O streams not available")
      }
    }
  }

  // Read response with timeout to avoid indefinite blocking
  private def readResponseWithTimeout(reader: java.io.BufferedReader, requestId: String): String = {
    val startTime = System.currentTimeMillis()

    while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
      if (reader.ready()) {
        val line = reader.readLine()
        if (line != null && line.trim.nonEmpty) {
          return line
        }
      }

      // Check if process is still alive
      process match {
        case Some(p) if !p.isAlive =>
          val stderrOutput = readAvailableStderr()
          throw new RuntimeException(
            s"MCP server process died while waiting for response to request $requestId. Stderr: $stderrOutput"
          )
        case _ =>
      }

      Thread.sleep(50) // Small delay before checking again
    }

    val stderrOutput = readAvailableStderr()
    val errorMsg =
      s"Timeout waiting for response to request $requestId after ${RESPONSE_TIMEOUT_MS}ms. Server stderr: $stderrOutput"
    throw new RuntimeException(errorMsg)
  }

  // Clean up process and streams
  private def cleanupProcess(): Unit = {
    stdinWriter.foreach { writer =>
      Try(writer.close()).recover { case e =>
        logger.debug(s"Error closing stdin writer: ${e.getMessage}")
      }
    }
    stdinWriter = None

    stdoutReader.foreach { reader =>
      Try(reader.close()).recover { case e =>
        logger.debug(s"Error closing stdout reader: ${e.getMessage}")
      }
    }
    stdoutReader = None

    stderrReader.foreach { reader =>
      Try(reader.close()).recover { case e =>
        logger.debug(s"Error closing stderr reader: ${e.getMessage}")
      }
    }
    stderrReader = None

    process.foreach { p =>
      Try {
        // First try graceful termination
        p.destroy()

        // Wait a bit for graceful shutdown
        val terminated = p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        if (!terminated) {
          logger.debug(s"StdioTransport($name) forcing process termination")
          p.destroyForcibly()
        }

        logger.debug(s"StdioTransport($name) process terminated with exit code: ${p.exitValue()}")
      }.recover { case e =>
        logger.warn(s"StdioTransport($name) error during process cleanup: ${e.getMessage}")
      }
    }
    process = None
    // Process and streams cleaned up
  }

  // Sends JSON-RPC notification via subprocess stdin (no response expected)
  override def sendNotification(notification: JsonRpcNotification): Either[String, Unit] = {
    logger.debug(s"StdioTransport($name) sending notification: method=${notification.method}")

    getOrStartProcess().flatMap { _ =>
      stdinWriter match {
        case Some(writer) =>
          Try {
            // Read any pending stderr for diagnostics
            readAvailableStderr()

            val notificationJson = write(notification)
            logger.info(s"StdioTransport($name) writing notification to stdin: $notificationJson")

            // Write notification as line-delimited JSON (one complete JSON object per line)
            writer.println(notificationJson)
            writer.flush() // Ensure the notification is immediately sent to the server

            if (writer.checkError()) {
              throw new RuntimeException("Failed to write notification to process stdin (broken pipe)")
            }

            // For notifications, we don't wait for a response - just return success
            logger.debug(s"StdioTransport($name) notification sent successfully")
            ()
          } match {
            case Success(_) =>
              logger.debug(s"StdioTransport($name) notification successful")
              Right(())
            case Failure(exception) =>
              // Read stderr for additional context
              val stderrOutput = readAvailableStderr()
              val errorMsg = if (stderrOutput.nonEmpty) {
                s"${exception.getMessage}. Server stderr: $stderrOutput"
              } else {
                exception.getMessage
              }

              logger.error(s"StdioTransport($name) notification error: $errorMsg", exception)
              Left(s"Stdio notification error: $errorMsg")
          }
        case None =>
          Left("Process stdin writer not available")
      }
    }
  }

  // Terminates the subprocess and closes streams
  override def close(): Unit = {
    logger.info(s"StdioTransport($name) closing process and streams")
    cleanupProcess()
  }

  // Generates unique request IDs
  def generateId(): String = requestId.incrementAndGet().toString
}

// Factory for creating transport implementations
object MCPTransport {
  // Creates appropriate transport implementation based on configuration
  def create(config: MCPServerConfig): MCPTransportImpl =
    config.transport match {
      case StdioTransport(command, name)      => new StdioTransportImpl(command, name)
      case SSETransport(url, name)            => new SSETransportImpl(url, name, config.timeout)
      case StreamableHTTPTransport(url, name) => new StreamableHTTPTransportImpl(url, name, config.timeout)
    }
}
