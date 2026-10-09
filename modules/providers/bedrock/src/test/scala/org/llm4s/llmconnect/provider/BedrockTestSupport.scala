package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.HttpExchange
import org.llm4s.llmconnect.config.{ BedrockConfig, BedrockCredentials }
import org.llm4s.testkit.LocalProviderTestServer
import software.amazon.eventstream.{ HeaderValue, Message }

import java.nio.charset.StandardCharsets
import scala.jdk.CollectionConverters.*

/**
 * Shared fixtures for the Bedrock specs: a config pointed at a local server, canned Converse
 * responses, and ConverseStream's binary event-stream framing, which is what the AWS SDK's
 * async client parses (not SSE, not newline JSON).
 */
private[provider] object BedrockTestSupport {

  val Model = "amazon.titan-text-express-v1"

  def config(endpointUrl: String, model: String = Model): BedrockConfig =
    BedrockConfig(
      region = "us-east-1",
      model = model,
      contextWindow = 32000,
      reserveCompletion = 4096,
      credentials = Some(BedrockCredentials("test-key-id", "test-secret-key")),
      endpointUrl = Some(endpointUrl)
    )

  def converseResponse(text: String): String =
    ujson
      .Obj(
        "output" -> ujson.Obj(
          "message" -> ujson.Obj("role" -> "assistant", "content" -> ujson.Arr(ujson.Obj("text" -> text)))
        ),
        "stopReason" -> "end_turn",
        "usage"      -> ujson.Obj("inputTokens" -> 10, "outputTokens" -> 8, "totalTokens" -> 18)
      )
      .render()

  def errorBody(errorType: String, message: String): String =
    ujson.Obj("__type" -> errorType, "message" -> message).render()

  val EventStreamContentType = "application/vnd.amazon.eventstream"

  /** One event frame, as Bedrock sends it: prelude, headers, JSON payload and CRCs. */
  def eventFrame(eventType: String, json: String): Array[Byte] = {
    val headers = Map(
      ":message-type" -> HeaderValue.fromString("event"),
      ":event-type"   -> HeaderValue.fromString(eventType),
      ":content-type" -> HeaderValue.fromString("application/json")
    )
    toBytes(new Message(headers.asJava, json.getBytes(StandardCharsets.UTF_8)))
  }

  /** An in-stream error, which the SDK surfaces as the exception named by `exceptionType`. */
  def exceptionFrame(exceptionType: String, message: String): Array[Byte] = {
    val headers = Map(
      ":message-type"   -> HeaderValue.fromString("exception"),
      ":exception-type" -> HeaderValue.fromString(exceptionType),
      ":content-type"   -> HeaderValue.fromString("application/json")
    )
    toBytes(new Message(headers.asJava, ujson.Obj("message" -> message).render().getBytes(StandardCharsets.UTF_8)))
  }

  private def toBytes(message: Message): Array[Byte] = {
    val buffer = message.toByteBuffer
    val bytes  = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    bytes
  }

  def textDelta(text: String, index: Int = 0): Array[Byte] =
    eventFrame(
      "contentBlockDelta",
      ujson.Obj("contentBlockIndex" -> index, "delta" -> ujson.Obj("text" -> text)).render()
    )

  /** A complete text answer in two deltas, a stop and usage metadata. */
  def textStream(first: String, second: String): Seq[Array[Byte]] = Seq(
    eventFrame("messageStart", """{"role":"assistant"}"""),
    textDelta(first),
    textDelta(second),
    eventFrame("contentBlockStop", """{"contentBlockIndex":0}"""),
    eventFrame("messageStop", """{"stopReason":"end_turn"}"""),
    eventFrame(
      "metadata",
      """{"usage":{"inputTokens":10,"outputTokens":5,"totalTokens":15},"metrics":{"latencyMs":42}}"""
    )
  )

  /** Answers with `frames` as a chunked event stream. */
  def sendEventStream(exchange: HttpExchange, frames: Seq[Array[Byte]]): Unit = {
    exchange.getResponseHeaders.add("Content-Type", EventStreamContentType)
    exchange.sendResponseHeaders(200, 0)
    val os = exchange.getResponseBody
    frames.foreach(os.write)
    os.close()
  }

  /** Sends `frames`, then never finishes the response: a stream that stalls. */
  def streamFramesThenHold(exchange: HttpExchange, frames: Seq[Array[Byte]]): Unit = {
    exchange.getResponseHeaders.add("Content-Type", EventStreamContentType)
    exchange.sendResponseHeaders(200, 0)
    val os = exchange.getResponseBody
    frames.foreach(os.write)
    os.flush()
    LocalProviderTestServer.holdOpen(exchange)
    scala.util.Try(os.close()): Unit
  }

  def isStreamRequest(exchange: HttpExchange): Boolean =
    exchange.getRequestURI.getPath.endsWith("/converse-stream")
}
