package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ DeepSeekConfig, OpenAICompatibleConfig }
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import scala.collection.mutable.ListBuffer

/**
 * Token usage on a streamed completion (#1132).
 *
 * None of the clients `OpenAICompatibleClient` replaced read the `usage` a stream reports on
 * its last event, so every streamed completion - DeepSeek's, Z.ai's, OpenRouter's, and now
 * Mistral's and Cohere's - came back with `usage = None` and no cost estimate, although the
 * provider had sent both counts. (#970 found the same gap in its Mistral streaming client.)
 */
class OpenAICompatibleStreamedUsageSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val client = new OpenAICompatibleClient(
    OpenAICompatibleClient.settings(OpenAICompatibleConfig("gpt-4o-mini", "http://localhost:1/v1", None)),
    OpenAICompatibleDialect.Standard
  )

  private def stream(events: String*): ByteArrayInputStream =
    new ByteArrayInputStream(
      (events.map(e => s"data: $e").mkString("\n\n") + "\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8)
    )

  private def delta(text: String, finish: String = "null", extra: String = "") =
    s"""{"id":"s","choices":[{"index":0,"delta":{"content":"$text"},"finish_reason":$finish}]$extra}"""

  "a streamed completion" should "report the usage sent on the final delta, and estimate its cost" in {
    val completion = client
      .consumeStream(
        200,
        stream(
          delta("Hel", extra = ""","usage":null"""),
          delta("lo", "\"stop\"", ""","usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}""")
        ),
        new StringBuilder,
        _ => ()
      )
      .value

    completion.content shouldBe "Hello"
    completion.usage shouldBe Some(TokenUsage(10, 5, 15))
    completion.estimatedCost shouldBe CostEstimator.estimate("gpt-4o-mini", TokenUsage(10, 5, 15))
  }

  it should "report usage sent on an event of its own, with no choices" in {
    val completion = client
      .consumeStream(
        200,
        stream(
          delta("hi", "\"stop\""),
          """{"id":"s","choices":[],"usage":{"prompt_tokens":3,"completion_tokens":2,"total_tokens":5}}"""
        ),
        new StringBuilder,
        _ => ()
      )
      .value

    completion.content shouldBe "hi"
    completion.usage shouldBe Some(TokenUsage(3, 2, 5))
  }

  it should "ignore a usage report without both counts rather than fail the stream" in {
    val completion = client
      .consumeStream(
        200,
        stream(delta("hi", "\"stop\"", ""","usage":{"prompt_tokens":3}""")),
        new StringBuilder,
        _ => ()
      )
      .value

    completion.content shouldBe "hi"
    completion.usage shouldBe None
  }

  it should "carry the dialect's reasoning tokens" in {
    val deepSeek = new DeepSeekClient(DeepSeekConfig("k", "deepseek-reasoner", "http://localhost:1", 128000, 8192))
    val usage =
      ""","usage":{"prompt_tokens":4,"completion_tokens":9,"total_tokens":13,""" +
        """"completion_tokens_details":{"reasoning_tokens":6}}"""

    deepSeek
      .consumeStream(200, stream(delta("ok", "\"stop\"", usage)), new StringBuilder, _ => ())
      .value
      .usage shouldBe Some(TokenUsage(4, 9, 13, Some(6)))
  }

  it should "reach the caller of streamComplete, with one exchange recorded" in {
    val recorded = ListBuffer.empty[ProviderExchange]
    val sink = new ProviderExchangeSink:
      override def record(exchange: ProviderExchange): Unit = recorded += exchange

    withServer("/chat/completions")(exchange => sendSseResponse(exchange, openAISseBody(Seq("a", "b")))) { baseUrl =>
      val c = OpenAICompatibleClient(
        OpenAICompatibleConfig("m", baseUrl, None),
        exchangeLogging = ProviderExchangeLogging.enabled(sink)
      ).value
      c.streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ()).value.usage shouldBe Some(
        TokenUsage(10, 5, 15)
      )
    }
    recorded should have size 1
  }

  // ==========================================================================
  // Asking for usage: stream_options.include_usage
  // ==========================================================================

  /** Runs one streaming call against a local server, returning the request body it sent and the completion. */
  private def streamOnce(dialect: OpenAICompatibleDialect, sse: String): (ujson.Value, Completion) = {
    var sent: ujson.Value = ujson.Null
    var completion        = Option.empty[Completion]
    withServer("/chat/completions") { exchange =>
      sent = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendSseResponse(exchange, sse)
    } { baseUrl =>
      val c = new OpenAICompatibleClient(
        OpenAICompatibleClient.settings(OpenAICompatibleConfig("gpt-4o-mini", baseUrl, None)),
        dialect
      )
      completion = Some(c.streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ()).value)
    }
    (sent, completion.get)
  }

  /** What vLLM and OpenAI send when asked: the final delta, then a usage-only event with empty `choices`. */
  private def usageOnlyFinalEvent: String =
    Seq(
      delta("Hel", extra = ""","usage":null"""),
      delta("lo", "\"stop\"", ""","usage":null"""),
      """{"id":"s","choices":[],"usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}"""
    ).map(e => s"data: $e").mkString("", "\n\n", "\n\ndata: [DONE]\n\n")

  "a streaming request" should "ask for usage with stream_options.include_usage, and read it from a final event with no choices" in {
    val (sent, completion) = streamOnce(OpenAICompatibleDialect.Standard, usageOnlyFinalEvent)

    sent("stream") shouldBe ujson.True
    sent("stream_options") shouldBe ujson.Obj("include_usage" -> true)
    completion.content shouldBe "Hello"
    completion.usage shouldBe Some(TokenUsage(7, 2, 9))
  }

  it should "not send stream_options for a dialect that opts out, and still read usage the server volunteers" in {
    val (sent, completion) = streamOnce(MistralDialect, usageOnlyFinalEvent)

    sent.obj.contains("stream_options") shouldBe false
    completion.usage shouldBe Some(TokenUsage(7, 2, 9))
  }

  "a non-streaming request" should "never carry stream_options" in {
    val c = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", "http://localhost:1/v1", None)),
      OpenAICompatibleDialect.Standard
    )
    c.createRequestBody(Conversation(Seq(UserMessage("hi"))), CompletionOptions())
      .obj
      .contains(
        "stream_options"
      ) shouldBe false
  }

  "streamUsageOption" should "be on for the standard dialect and DeepSeek, which document stream_options" in {
    OpenAICompatibleDialect.Standard.streamUsageOption shouldBe true
    DeepSeekDialect.streamUsageOption shouldBe true
  }

  it should "be off for Z.ai, OpenRouter, Mistral and Cohere" in {
    // Z.ai and Cohere do not document the field; OpenRouter documents it as a no-op, since it
    // always streams usage; Mistral rejects unknown fields with a 422.
    ZaiDialect.streamUsageOption shouldBe false
    OpenRouterDialect.streamUsageOption shouldBe false
    MistralDialect.streamUsageOption shouldBe false
    CohereDialect.streamUsageOption shouldBe false
  }

  it should "follow the generic provider's streamUsage setting, on by default" in {
    OpenAICompatibleConfig.fromValues("m", "http://localhost:1/v1").value.streamUsage shouldBe true
    OpenAICompatibleDialect.standard(streamUsage = false).streamUsageOption shouldBe false

    var sent: ujson.Value = ujson.Null
    withServer("/chat/completions") { exchange =>
      sent = ujson.read(new String(exchange.getRequestBody.readAllBytes(), StandardCharsets.UTF_8))
      sendSseResponse(exchange, openAISseBody(Seq("a")))
    } { baseUrl =>
      val config = OpenAICompatibleConfig.fromValues("m", baseUrl, streamUsage = false).value
      OpenAICompatibleClient(config).value
        .streamComplete(Conversation(Seq(UserMessage("hi"))), CompletionOptions(), _ => ())
        .value
    }
    sent.obj.contains("stream_options") shouldBe false
  }
}
