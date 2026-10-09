package org.llm4s.mcp

import ch.qos.logback.classic.{ Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.llm4s.http.{ HttpResponse, Llm4sHttpClient }
import org.llm4s.types.Result
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory
import upickle.default._

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Try

/**
 * An MCP server's HTTP error body, or the message of its JSON-RPC error, can echo the request's credentials. Neither
 * may reach the caller's error or the log in the clear (#1674): the transports redact before they truncate.
 */
class MCPTransportRedactionSpec extends AnyWordSpec with Matchers with MockFactory {

  private val BearerToken = "ya29.bearer-secret-token-value"
  private val ApiKeyValue = "s3cr3t-api-key-value"
  private val GoogleKey   = "AIzaSyA1234567890abcdefghijklmnopqrstuv"
  private val Secrets     = Seq(BearerToken, ApiKeyValue, GoogleKey)

  private val EchoedText =
    s"Authorization: Bearer $BearerToken\n" +
      s"""{"api_key": "$ApiKeyValue"}""" + "\n" +
      s"GET /mcp?key=$GoogleKey"

  private def assertNoSecret(text: String): Unit =
    Secrets.foreach(secret => withClue(s"in: $text\n")((text should not).include(secret)))

  private val request      = JsonRpcRequest(jsonrpc = "2.0", method = "initialize", id = "1", params = None)
  private val notification = JsonRpcNotification(jsonrpc = "2.0", method = "notifications/initialized", params = None)

  private val rpcError = write(
    JsonRpcResponse(
      jsonrpc = "2.0",
      id = "1",
      result = None,
      error = Some(JsonRpcError(code = -32600, message = EchoedText, data = None))
    )
  )

  /** Runs `f` and returns its result with every log line written meanwhile. */
  private def logged[A](f: => A): (A, Seq[String]) = {
    val root     = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    root.addAppender(appender)
    val result = Try(f)
    root.detachAppender(appender)
    (result.get, appender.list.asScala.toSeq.map(_.getFormattedMessage))
  }

  private def assertRedacted(outcome: (Result[?], Seq[String])): Unit = {
    val (result, lines) = outcome
    val message         = result.left.getOrElse(fail(s"expected a failure, got $result")).message
    message should include("[REDACTED]")
    assertNoSecret(message)
    lines.foreach(assertNoSecret)
  }

  private def answering(status: Int, body: String): Llm4sHttpClient = {
    val http = stub[Llm4sHttpClient]
    (http.post _).when(*, *, *, *).returns(Right(HttpResponse(status, body)))
    http
  }

  private val transports: Seq[(String, Llm4sHttpClient => MCPTransportImpl)] = Seq(
    "streamable-http" -> (http => new StreamableHTTPTransportImpl("http://localhost:8080/mcp", "t", 5.seconds, http)),
    "sse"             -> (http => new SSETransportImpl("http://localhost:8080/mcp", "t", 5.seconds, http))
  )

  "every HTTP transport" should {

    "redact credentials echoed in an HTTP error body of a request" in {
      transports.foreach { case (name, transport) =>
        withClue(s"$name: ")(assertRedacted(logged(transport(answering(500, EchoedText)).sendRequest(request))))
      }
    }

    "redact credentials echoed in an HTTP error body of a notification" in {
      transports.foreach { case (name, transport) =>
        withClue(s"$name: ") {
          assertRedacted(logged(transport(answering(500, EchoedText)).sendNotification(notification)))
        }
      }
    }

    "redact credentials echoed in the message of a JSON-RPC error" in {
      transports.foreach { case (name, transport) =>
        withClue(s"$name: ")(assertRedacted(logged(transport(answering(200, rpcError)).sendRequest(request))))
      }
    }

    "redact credentials in an unrecognized SSE line it logs at DEBUG" in {
      val ok   = write(JsonRpcResponse(jsonrpc = "2.0", id = "1", result = Some(ujson.Obj()), error = None))
      val body = EchoedText + "\n" + s"data: $ok\n\n"
      val http = stub[Llm4sHttpClient]
      (http.post _)
        .when(*, *, *, *)
        .returns(Right(HttpResponse(200, body, Map("content-type" -> Seq("text/event-stream")))))
      val mcpLogger = LoggerFactory.getLogger("org.llm4s.mcp").asInstanceOf[LogbackLogger]
      val previous  = mcpLogger.getLevel
      mcpLogger.setLevel(ch.qos.logback.classic.Level.DEBUG)
      val outcome =
        Try(
          logged(
            new StreamableHTTPTransportImpl("http://localhost:8080/mcp", "t", 5.seconds, http).sendRequest(request)
          )
        )
      mcpLogger.setLevel(previous)
      val (result, lines) = outcome.get
      result.isRight shouldBe true
      val ignored = lines.filter(_.contains("ignoring unrecognized SSE line"))
      ignored should not be empty
      ignored.exists(_.contains("[REDACTED]")) shouldBe true
      lines.foreach(assertNoSecret)
    }
  }
}
