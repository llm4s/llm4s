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

// Server-Sent Events transport using HTTP
case class SSETransport(url: String, name: String) extends MCPTransport

// Base trait for transport implementations
trait MCPTransportImpl {
  def name: String
  // Sends a JSON-RPC request and waits for response
  def sendRequest(request: JsonRpcRequest): Either[String, JsonRpcResponse]
  // Closes the transport connection
  def close(): Unit
}

// SSE transport implementation using HTTP client
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
      case StdioTransport(command, name) => new StdioTransportImpl(command, name)
      case SSETransport(url, name)       => new SSETransportImpl(url, name)
    }
}
