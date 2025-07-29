package org.llm4s.mcp

import scala.util.{ Try, Success, Failure }
import java.util.concurrent.atomic.AtomicLong
import upickle.default._
import org.slf4j.LoggerFactory

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
  // Closes the transport connection
  def close(): Unit
}

// Session management for Streamable HTTP
case class MCPSession(
  sessionId: String,
  lastEventId: Option[String] = None
)

// Streamable HTTP transport implementation (2025-06-18 spec)
class StreamableHTTPTransportImpl(url: String, override val name: String) extends MCPTransportImpl {
  private val logger                       = LoggerFactory.getLogger(getClass)
  private val requestId                    = new AtomicLong(0)
  private var mcpSessionId: Option[String] = None

  logger.info(s"StreamableHTTPTransport($name) initialized for URL: $url")

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
        headers = headers
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

      // Determine response type - for now assume JSON unless response body looks like SSE
      val responseBody = response.text()
      val isSSE        = responseBody.contains("data: ")

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
            logger.debug(s"StreamableHTTPTransport($name) skipping non-JSON-RPC SSE data: $data")
            None
        }
      }

    // Return the first valid JSON-RPC response
    jsonResponses.headOption.getOrElse {
      throw new RuntimeException("No valid JSON-RPC response found in SSE stream")
    }
  }

  override def close(): Unit = {
    logger.info(s"StreamableHTTPTransport($name) closing connection to $url")

    // Send DELETE request to explicitly terminate session if we have one
    mcpSessionId.foreach { sessionId =>
      Try {
        requests.delete(
          url,
          headers = Map("mcp-session-id" -> sessionId) // lowercase per spec
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
class SSETransportImpl(url: String, override val name: String) extends MCPTransportImpl {
  private val logger    = LoggerFactory.getLogger(getClass)
  private val requestId = new AtomicLong(0)

  logger.info(s"SSETransport($name) initialized for URL: $url")

  // Sends JSON-RPC request via HTTP POST
  override def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse] = {
    logger.debug(s"SSETransport($name) sending request to $url: method=${request.method}, id=${request.id}")

    Try {
      val requestJson = write(request)
      logger.debug(s"SSETransport($name) request JSON: $requestJson")

      val response = requests.post(
        s"$url/sse",
        data = requestJson,
        headers = Map("Content-Type" -> "application/json")
      )

      logger.debug(s"SSETransport($name) received HTTP response: status=${response.statusCode}")
      // will throw RequestFailedException on any status code that is not success (2xx)

      val jsonResponse = read[JsonRpcResponse](response.text())
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

  // Closes the HTTP client connection
  override def close(): Unit =
    logger.info(s"SSETransport($name) closing connection to $url")
    // SSE connections are stateless, nothing to close

  // Generates unique request IDs
  def generateId(): String = requestId.incrementAndGet().toString
}

// Stdio transport implementation using subprocess communication
class StdioTransportImpl(command: Seq[String], override val name: String) extends MCPTransportImpl {
  private val logger                   = LoggerFactory.getLogger(getClass)
  private var process: Option[Process] = None
  private val requestId                = new AtomicLong(0)

  logger.info(s"StdioTransport($name) initialized with command: ${command.mkString(" ")}")

  // Gets existing process or starts new one if needed
  private def getOrStartProcess(): Either[String, Process] =
    process match {
      case Some(p) if p.isAlive =>
        logger.debug(s"StdioTransport($name) reusing existing process")
        Right(p)
      case _ =>
        logger.info(s"StdioTransport($name) starting new process: ${command.mkString(" ")}")
        Try {
          val processBuilder = new ProcessBuilder(command: _*)
          val newProcess     = processBuilder.start()
          process = Some(newProcess)
          logger.info(s"StdioTransport($name) process started successfully")
          newProcess
        } match {
          case Success(p) => Right(p)
          case Failure(e) =>
            logger.error(s"StdioTransport($name) failed to start process: ${e.getMessage}", e)
            Left(s"Failed to start MCP server process: ${e.getMessage}")
        }
    }

  // Sends JSON-RPC request via subprocess stdin/stdout
  override def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse] = {
    logger.debug(s"StdioTransport($name) sending request: method=${request.method}, id=${request.id}")

    getOrStartProcess().flatMap { proc =>
      Try {
        val requestJson = write(request)
        logger.debug(s"StdioTransport($name) writing to stdin: $requestJson")

        // Write request to process stdin
        val writer = new java.io.OutputStreamWriter(proc.getOutputStream, "UTF-8")
        writer.write(requestJson + "\n")
        writer.flush()

        // Read response from process stdout
        val reader = new java.io.BufferedReader(
          new java.io.InputStreamReader(proc.getInputStream, "UTF-8")
        )
        val responseLine = reader.readLine()

        if (responseLine == null) {
          throw new RuntimeException("No response from MCP server")
        }

        logger.debug(s"StdioTransport($name) received from stdout: $responseLine")
        val response = read[JsonRpcResponse](responseLine)
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
          logger.error(s"StdioTransport($name) transport error: ${exception.getMessage}", exception)
          Left(s"Stdio transport error: ${exception.getMessage}")
      }
    }
  }

  // Terminates the subprocess and closes streams
  override def close(): Unit = {
    logger.info(s"StdioTransport($name) closing process and streams")
    process.foreach { p =>
      Try {
        p.getOutputStream.close()
        p.getInputStream.close()
        p.getErrorStream.close()
        p.destroyForcibly()
        logger.debug(s"StdioTransport($name) process terminated")
      }.recover { case e => logger.warn(s"StdioTransport($name) error during cleanup: ${e.getMessage}") }
      process = None
    }
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
      case SSETransport(url, name)            => new SSETransportImpl(url, name)
      case StreamableHTTPTransport(url, name) => new StreamableHTTPTransportImpl(url, name)
    }
}
