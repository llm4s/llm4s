package org.llm4s.llmconnect.provider

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{
  ContextWindowResolver,
  EmbeddingModelConfig,
  EmbeddingProviderConfig,
  OpenAIConfig,
  ProviderTimeouts
}
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, EmbeddingRequest, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches the OpenAI, Azure and Requesty clients and the OpenAI
 * embedding provider (#712).
 *
 * The chat clients sit on `openai-java`, whose client-level timeout is one value for every call and
 * covers a streamed response in full, so the timeouts go on each call instead. The last tests here pin
 * what that buys: a short `request` timeout does not cut a stream.
 */
final class OpenAIConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given mrs: ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver     = ContextWindowResolver(mrs)

  private def hello: Conversation = Conversation(Seq(UserMessage("hello")))

  // ---- HOCON -> descriptor -> config ----

  private def load(provider: String, extra: String, timeouts: String) =
    Llm4sConfig
      .provider(
        ConfigSource.string(
          s"""llm4s.providers.main {
             |  provider = "$provider"
             |  model    = "gpt-4o"
             |  apiKey   = "sk-test"
             |$extra
             |$timeouts
             |}
             |""".stripMargin
        ),
        "main"
      )
      .value

  "The OpenAI, Azure and Requesty descriptors" should "carry the section's timeouts on their configs" in {
    val block    = "timeouts { request = 7s, stream = 11s }"
    val expected = ProviderTimeouts(Some(7.seconds), Some(11.seconds))
    load("openai", "", block).timeouts shouldBe expected
    load("requesty", "", block).timeouts shouldBe expected
    load(
      "azure",
      """endpoint = "https://my-resource.openai.azure.com"
                    |apiVersion = "2024-02-01"""".stripMargin,
      block
    ).timeouts shouldBe expected
  }

  they should "leave the timeouts unset for a section without the block" in {
    load("openai", "", "").timeouts shouldBe ProviderTimeouts.default
  }

  // ---- the value reaches the call ----

  private def openAIConfig(baseUrl: String, timeouts: ProviderTimeouts): OpenAIConfig =
    OpenAIConfig.fromValues("gpt-4o", "sk-test", None, baseUrl).value.withTimeouts(timeouts)

  "A configured request timeout" should "end a completion from a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val client = OpenAIClient(openAIConfig(baseUrl, ProviderTimeouts(request = Some(300.millis)))).value
      try {
        val started = System.nanoTime()
        val result  = client.complete(hello, CompletionOptions())
        val elapsed = (System.nanoTime() - started).nanos

        result.isLeft shouldBe true
        // The SDK's own default is ten minutes: the configured 300ms must be what ended the call. The
        // SDK retries a timed-out call twice, so allow for three attempts and their backoff.
        elapsed should be < 30.seconds
      } finally client.close()
    }
  }

  "A configured stream timeout" should "end a stream that stalls after its first event" in {
    val first = "data: " + ujson.write(
      ujson.Obj(
        "id"      -> "c1",
        "object"  -> "chat.completion.chunk",
        "created" -> 0,
        "model"   -> "gpt-4o",
        "choices" -> ujson.Arr(
          ujson.Obj("index" -> 0, "delta" -> ujson.Obj("role" -> "assistant", "content" -> "hi"))
        )
      )
    ) + "\n\n"
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.streamThenHold(_, first)) { baseUrl =>
      val client = OpenAIClient(openAIConfig(baseUrl, ProviderTimeouts(stream = Some(500.millis)))).value
      try {
        val started = System.nanoTime()
        val result  = client.streamComplete(hello, CompletionOptions(), _ => ())
        val elapsed = (System.nanoTime() - started).nanos

        result.isLeft shouldBe true
        elapsed should be < 30.seconds
      } finally client.close()
    }
  }

  "The request and stream timeouts" should "be independent: a short request timeout does not cut a longer stream" in {
    // The reply starts after 800ms, longer than the 300ms request timeout and well within the stream's.
    LocalProviderTestServer.withServer("/") { exchange =>
      Thread.sleep(800)
      LocalProviderTestServer.sendSseResponse(
        exchange,
        LocalProviderTestServer.openAISseBody(Seq("hi"), "gpt-4o")
      )
    } { baseUrl =>
      val client = OpenAIClient(
        openAIConfig(baseUrl, ProviderTimeouts(request = Some(300.millis), stream = Some(20.seconds)))
      ).value
      try {
        val result = client.streamComplete(hello, CompletionOptions(), _ => ())
        result.value.content shouldBe "hi"
      } finally client.close()
    }
  }

  it should "leave the SDK's own timeouts alone when the section sets none" in {
    OpenAIClientTransport.requestOptions(None) shouldBe None
    OpenAIClientTransport.requestOptions(Some(5.seconds)).isDefined shouldBe true
  }

  // ---- embeddings ----

  "A configured request timeout on an embedding section" should "end an embedding call to a server that never answers" in {
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen) { baseUrl =>
      val provider = OpenAIEmbeddingProvider.fromConfig(
        EmbeddingProviderConfig(
          baseUrl = baseUrl,
          model = "text-embedding-3-small",
          apiKey = "sk-test",
          timeouts = ProviderTimeouts(request = Some(300.millis))
        )
      )
      val started = System.nanoTime()
      val result  = provider.embed(EmbeddingRequest(Seq("hello"), EmbeddingModelConfig("text-embedding-3-small", 1536)))
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      elapsed should be < 15.seconds
    }
  }
}
