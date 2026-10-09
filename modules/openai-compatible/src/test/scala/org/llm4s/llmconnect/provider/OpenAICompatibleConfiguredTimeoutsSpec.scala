package org.llm4s.llmconnect.provider

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.config.{
  CohereConfig,
  DeepSeekConfig,
  MistralConfig,
  OpenAICompatibleConfig,
  OpenAIConfig,
  ProviderConfig,
  ProviderTimeouts,
  ZaiConfig
}
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testkit.LocalProviderTestServer._
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import scala.concurrent.duration.*

/**
 * The `timeouts` block of a section reaches every client of the OpenAI-compatible family (#712): from
 * HOCON, through the provider's descriptor, onto its config and into the request and stream timeouts its
 * client sends with.
 */
class OpenAICompatibleConfiguredTimeoutsSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private def Configured: ProviderTimeouts = ProviderTimeouts(Some(7.seconds), Some(11.seconds))

  /** A section of `provider` (plus whatever else it needs) with the `timeouts` block under test. */
  private def section(provider: String, extra: String, timeouts: String): String =
    s"""llm4s.providers.main {
       |  provider = "$provider"
       |  model    = "some-model"
       |  apiKey   = "k"
       |$extra
       |$timeouts
       |}
       |""".stripMargin

  private def load(provider: String, extra: String = "", timeouts: String): ProviderConfig =
    Llm4sConfig.provider(ConfigSource.string(section(provider, extra, timeouts)), "main").value

  private def block: String = "timeouts { request = 7s, stream = 11s }"

  // ---- HOCON -> descriptor -> config ----

  "Every provider of the family" should "carry the section's timeouts on the config its descriptor builds" in {
    val sections = Seq(
      "deepseek"          -> "",
      "zai"               -> "",
      "mistral"           -> "",
      "cohere"            -> "",
      "openrouter"        -> "",
      "openai-compatible" -> """baseUrl = "http://localhost:8000/v1""""
    )
    sections.foreach { case (provider, extra) =>
      withClue(s"provider '$provider': ") {
        load(provider, extra, block).timeouts shouldBe Configured
      }
    }
  }

  it should "leave the timeouts unset for a section without the block, so each client keeps its default" in {
    Seq("deepseek", "zai", "mistral", "cohere", "openrouter").foreach { provider =>
      withClue(s"provider '$provider': ") {
        load(provider, timeouts = "").timeouts shouldBe ProviderTimeouts.default
      }
    }
  }

  it should "take either timeout alone" in {
    load("deepseek", timeouts = "timeouts { request = 45s }").timeouts shouldBe
      ProviderTimeouts(request = Some(45.seconds))
    load("deepseek", timeouts = "timeouts { stream = 20m }").timeouts shouldBe
      ProviderTimeouts(stream = Some(20.minutes))
  }

  it should "refuse a non-positive timeout at load, naming the key" in {
    Llm4sConfig.provider(ConfigSource.string(section("deepseek", "", "timeouts { request = 0s }")), "main") match {
      case Left(error) =>
        error.message should include("llm4s.providers.main.timeouts.request")
      case Right(config) => fail(s"expected a refusal, got $config")
    }
  }

  // ---- config -> client ----

  "Each client" should "use the configured request and stream timeouts" in {
    val deepSeek = DeepSeekClient(DeepSeekConfig("k", "m", "http://localhost:1", 128000, 8192).withTimeouts(Configured))
    val zai      = ZaiClient(ZaiConfig("k", "m", "http://localhost:1", 128000, 8192).withTimeouts(Configured))
    val mistral  = MistralClient(MistralConfig("k", "m", "http://localhost:1", 128000, 4096).withTimeouts(Configured))
    val cohere   = CohereClient(CohereConfig("k", "m", "http://localhost:1", 128000, 4096).withTimeouts(Configured))
    val router   = OpenRouterClient(OpenAIConfig("k", "m").withBaseUrl("http://localhost:1").withTimeouts(Configured))
    val generic = new OpenAICompatibleClient(
      OpenAICompatibleClient.settings(OpenAICompatibleConfig("m", "http://localhost:1", None).withTimeouts(Configured)),
      OpenAICompatibleDialect.Standard
    )

    Seq(
      "deepseek" -> (deepSeek.value.requestTimeout, deepSeek.value.streamTimeout),
      "zai"      -> (zai.value.requestTimeout, zai.value.streamTimeout),
      "mistral"  -> (mistral.value.requestTimeout, mistral.value.streamTimeout),
      "cohere"   -> (cohere.value.requestTimeout, cohere.value.streamTimeout),
      "router"   -> (router.value.requestTimeout, router.value.streamTimeout),
      "generic"  -> (generic.requestTimeout, generic.streamTimeout)
    ).foreach { case (name, (request, stream)) =>
      withClue(s"client '$name': ") {
        request shouldBe 7.seconds
        stream shouldBe 11.seconds
      }
    }
  }

  it should "keep its own defaults when no timeout is configured" in {
    val deepSeek = DeepSeekClient(DeepSeekConfig("k", "m", "http://localhost:1", 128000, 8192)).value
    deepSeek.requestTimeout shouldBe OpenAICompatibleClient.RequestTimeout
    deepSeek.streamTimeout shouldBe OpenAICompatibleClient.StreamTimeout
    OpenAICompatibleClient.RequestTimeout shouldBe 2.minutes
    OpenAICompatibleClient.StreamTimeout shouldBe 5.minutes
  }

  it should "use a configured request timeout without disturbing the default stream timeout" in {
    val client = DeepSeekClient(
      DeepSeekConfig("k", "m", "http://localhost:1", 128000, 8192).withTimeouts(ProviderTimeouts(Some(3.seconds), None))
    ).value
    client.requestTimeout shouldBe 3.seconds
    client.streamTimeout shouldBe OpenAICompatibleClient.StreamTimeout
  }

  // ---- the value reaches the wire ----

  /** Runs `test` against a server that accepts each request and never answers it. */
  private def withSilentServer(test: String => Any): Unit = {
    // Released before the server stops, so stopping it does not wait on a parked handler.
    val release = new CountDownLatch(1)
    withServer("/chat/completions")(_ => release.await(10, TimeUnit.SECONDS)) { baseUrl =>
      try test(baseUrl)
      finally release.countDown()
    }
  }

  private def hi: Conversation = Conversation(Seq(UserMessage("hi")))

  "A configured request timeout" should "end a call to a server that never answers, long before the default would" in {
    withSilentServer { baseUrl =>
      val client =
        DeepSeekClient(
          DeepSeekConfig("k", "m", baseUrl, 128000, 8192).withTimeouts(ProviderTimeouts(Some(300.millis), None))
        ).value

      val started = System.nanoTime()
      val result  = client.complete(hi, CompletionOptions())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      result.left.value.message.toLowerCase should include("timed out")
      // The default is two minutes; the configured 300ms must be what ended the call.
      elapsed should be < 10.seconds
    }
  }

  "A configured stream timeout" should "end a streamed call to a server that never answers" in {
    withSilentServer { baseUrl =>
      val client =
        DeepSeekClient(
          DeepSeekConfig("k", "m", baseUrl, 128000, 8192).withTimeouts(ProviderTimeouts(None, Some(300.millis)))
        ).value

      val started = System.nanoTime()
      val result  = client.streamComplete(hi, CompletionOptions(), _ => ())
      val elapsed = (System.nanoTime() - started).nanos

      result.isLeft shouldBe true
      elapsed should be < 10.seconds
    }
  }

  "The request and stream timeouts" should "be independent: a short request timeout does not shorten a stream" in {
    val client = DeepSeekClient(
      DeepSeekConfig("k", "m", "http://localhost:1", 128000, 8192)
        .withTimeouts(ProviderTimeouts(Some(1.second), None))
    ).value
    client.requestTimeout shouldBe 1.second
    client.streamTimeout shouldBe 5.minutes
  }
}
