package org.llm4s.llmconnect.provider

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{ AnthropicConfig, ProviderTimeouts }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches the Anthropic client (#712).
 *
 * The client sits on the Anthropic SDK, whose client-level timeout is one value for every call and
 * covers a streamed response in full, so the timeouts go on each call instead. The last test pins what
 * that buys: a short `request` timeout does not cut a stream.
 */
final class AnthropicConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def hello: Conversation = Conversation(Seq(UserMessage("hello")))

  private def config(baseUrl: String, timeouts: ProviderTimeouts): AnthropicConfig =
    AnthropicConfig(
      apiKey = "test-key",
      model = "claude-3-5-sonnet-latest",
      baseUrl = baseUrl,
      contextWindow = 200000,
      reserveCompletion = 4096
    ).withTimeouts(timeouts)

  // ---- HOCON -> descriptor -> config ----

  private def load(timeouts: String) =
    Llm4sConfig
      .provider(
        ConfigSource.string(
          s"""llm4s.providers.main {
             |  provider = "anthropic"
             |  model    = "claude-3-5-sonnet-latest"
             |  apiKey   = "sk-ant-test"
             |$timeouts
             |}
             |""".stripMargin
        ),
        "main"
      )
      .value

  "The Anthropic descriptor" should "carry the section's timeouts on its config" in {
    load("timeouts { request = 7s, stream = 11s }").timeouts shouldBe
      ProviderTimeouts(Some(7.seconds), Some(11.seconds))
  }

  it should "leave the timeouts unset for a section without the block" in {
    load("").timeouts shouldBe ProviderTimeouts.default
  }

  // ---- the value reaches the call ----

  "A configured request timeout" should "end a completion from a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val client  = new AnthropicClient(config(baseUrl, ProviderTimeouts(request = Some(300.millis))))
      val started = System.nanoTime()
      val result  = client.complete(hello, CompletionOptions())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      // The SDK's own default is ten minutes: the configured 300ms must be what ended the call. The SDK
      // retries a timed-out call twice, so allow for three attempts and their backoff.
      elapsed should be < 30.seconds
    }
  }

  "A configured stream timeout" should "end a stream that stalls after its first event" in {
    val first =
      "event: message_start\ndata: " +
        """{"type":"message_start","message":{"id":"msg_1","type":"message","role":"assistant","content":[],""" +
        """"model":"claude-3-5-sonnet-latest","stop_reason":null,"stop_sequence":null,""" +
        """"usage":{"input_tokens":8,"output_tokens":0}}}""" + "\n\n"
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.streamThenHold(_, first)) { baseUrl =>
      val client  = new AnthropicClient(config(baseUrl, ProviderTimeouts(stream = Some(500.millis))))
      val started = System.nanoTime()
      val result  = client.streamComplete(hello, CompletionOptions(), _ => ())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      elapsed should be < 30.seconds
    }
  }

  "The request and stream timeouts" should "be independent: a short request timeout does not cut a longer stream" in {
    val events = Seq(
      "message_start" -> (
        """{"type":"message_start","message":{"id":"msg_s","type":"message","role":"assistant","content":[],""" +
          """"model":"claude-3-5-sonnet-latest","stop_reason":null,"stop_sequence":null,""" +
          """"usage":{"input_tokens":8,"output_tokens":0}}}"""
      ),
      "content_block_start" -> """{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
      "content_block_delta" ->
        """{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hello"}}""",
      "content_block_stop" -> """{"type":"content_block_stop","index":0}""",
      "message_delta" ->
        """{"type":"message_delta","delta":{"stop_reason":"end_turn","stop_sequence":null},"usage":{"output_tokens":3}}""",
      "message_stop" -> """{"type":"message_stop"}"""
    )
    val body = events.map { case (event, data) => s"event: $event\ndata: $data\n\n" }.mkString
    // The reply starts after 800ms, longer than the 300ms request timeout and well within the stream's.
    LocalProviderTestServer.withServer("/") { exchange =>
      Thread.sleep(800)
      LocalProviderTestServer.sendSseResponse(exchange, body)
    } { baseUrl =>
      val client = new AnthropicClient(
        config(baseUrl, ProviderTimeouts(request = Some(300.millis), stream = Some(20.seconds)))
      )
      val result = client.streamComplete(hello, CompletionOptions(), _ => ())
      result.value.content shouldBe "Hello"
    }
  }
}
