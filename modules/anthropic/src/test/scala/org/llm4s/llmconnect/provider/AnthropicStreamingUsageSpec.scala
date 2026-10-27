package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.model._
import org.llm4s.model.ModelRegistryService
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import scala.util.Using
import scala.util.Using.Releasable

/**
 * A streamed response's token usage: Anthropic sends the input and prompt-cache counts on
 * `message_start` and the cumulative output count on `message_delta`, which repeats the others
 * only when they changed. The streamed completion reports the same usage a non-streamed one does.
 */
class AnthropicStreamingUsageSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = AnthropicConfig(
    apiKey = "test-key",
    model = "claude-sonnet-4-5",
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  /** A fake `/v1/messages` answering every request with `events` as server-sent events. */
  private given Releasable[HttpServer] = _.stop(0)

  private def withStream[A](events: (String, String)*)(test: String => A): A = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext(
      "/v1/messages",
      (exchange: HttpExchange) => {
        exchange.getRequestBody.readAllBytes()
        val bytes =
          events.map((event, data) => s"event: $event\ndata: $data\n\n").mkString.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "text/event-stream")
        exchange.sendResponseHeaders(200, bytes.length)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    Using.resource(server)(s => test(s"http://localhost:${s.getAddress.getPort}"))
  }

  private def messageStart(usage: String) =
    "message_start" ->
      s"""{"type":"message_start","message":{"id":"msg_u","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-5","stop_reason":null,"stop_sequence":null,"usage":$usage}}"""

  private val text = Seq(
    "content_block_start" ->
      """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
    "content_block_delta" ->
      """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi."}}""",
    "content_block_stop" -> """{"type":"content_block_stop","index":0}"""
  )

  private def messageDelta(usage: String) =
    "message_delta" ->
      s"""{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":$usage}"""

  private val messageStop = "message_stop" -> """{"type":"message_stop"}"""

  private def streamedUsage(baseUrl: String): TokenUsage =
    new AnthropicClient(testConfig.withBaseUrl(baseUrl))
      .streamComplete(Conversation(Seq(UserMessage("Hello"))), CompletionOptions(), _ => ())
      .value
      .usage
      .value

  "a streamed response" should "report the input and cache counts from message_start with the output from message_delta" in {
    val events = Seq(
      messageStart(
        """{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":3000,"cache_creation_input_tokens":250}"""
      )
    ) ++ text ++ Seq(messageDelta("""{"output_tokens":40}"""), messageStop)
    withStream(events*) { baseUrl =>
      streamedUsage(baseUrl) shouldBe TokenUsage(
        promptTokens = 12,
        completionTokens = 40,
        totalTokens = 52,
        cachedTokens = Some(3000),
        cacheCreationTokens = Some(250)
      )
    }
  }

  it should "take the counts message_delta repeats over those of message_start" in {
    val events = Seq(
      messageStart("""{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":0}""")
    ) ++ text ++ Seq(
      messageDelta(
        """{"input_tokens":20,"output_tokens":40,"cache_read_input_tokens":3000,"cache_creation_input_tokens":null}"""
      ),
      messageStop
    )
    withStream(events*) { baseUrl =>
      val usage = streamedUsage(baseUrl)
      usage.promptTokens shouldBe 20
      usage.completionTokens shouldBe 40
      usage.cachedTokens shouldBe Some(3000)
      usage.cacheCreationTokens shouldBe None
    }
  }

  it should "leave the cache counts out when Anthropic does not report them" in {
    val events =
      Seq(messageStart("""{"input_tokens":12,"output_tokens":1}""")) ++ text ++
        Seq(messageDelta("""{"output_tokens":40}"""), messageStop)
    withStream(events*) { baseUrl =>
      val usage = streamedUsage(baseUrl)
      usage.promptTokens shouldBe 12
      usage.cachedTokens shouldBe None
      usage.cacheCreationTokens shouldBe None
    }
  }

  it should "keep the counts it has when a message_delta carries no readable usage" in {
    val events =
      Seq(messageStart("""{"input_tokens":12,"output_tokens":1,"cache_read_input_tokens":3000}""")) ++ text ++
        Seq(
          "message_delta" -> """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null}}""",
          messageStop
        )
    withStream(events*) { baseUrl =>
      streamedUsage(baseUrl) shouldBe TokenUsage(
        promptTokens = 12,
        completionTokens = 1,
        totalTokens = 13,
        cachedTokens = Some(3000),
        cacheCreationTokens = None
      )
    }
  }
}
