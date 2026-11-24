package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId, ProviderName }
import org.llm4s.security.Secret
import org.llm4s.config.Llm4sConfig
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import scala.concurrent.duration.*

/** `XaiConfig` (#1023): validation, the context-window fallback, redaction, and loading a section. */
class XaiConfigSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  "XaiConfig.fromValues" should "refuse a blank apiKey or baseUrl" in {
    XaiConfig.fromValues(ModelName("grok-4.7"), Secret(""), XaiConfig.DEFAULT_BASE_URL).isLeft shouldBe true
    XaiConfig.fromValues(ModelName("grok-4.7"), Secret("k"), BaseUrl(" ")).isLeft shouldBe true
  }

  it should "build a config for the xai provider at the given base URL" in {
    val config = XaiConfig.fromValues(ModelName("grok-4.7"), Secret("k"), XaiConfig.DEFAULT_BASE_URL).value
    config.providerId shouldBe ProviderId("xai")
    config.model.asString shouldBe "grok-4.7"
    config.baseUrl shouldBe BaseUrl("https://api.x.ai/v1")
    config.endpointUrl shouldBe Some("https://api.x.ai/v1")
    config.contextWindow should be > 0
    config.withModel(ModelName("grok-3-mini")).model.asString shouldBe "grok-3-mini"
    config.withTimeouts(ProviderTimeouts(request = Some(5.seconds))).timeouts.request shouldBe Some(5.seconds)
  }

  it should "take a model's window from the registry when it lists the model" in {
    // the registry lists xai/grok-3-mini
    XaiConfig
      .fromValues(ModelName("grok-3-mini"), Secret("k"), XaiConfig.DEFAULT_BASE_URL)
      .value
      .contextWindow should be > 0
  }

  "the fallback window" should "follow xAI's models page" in {
    XaiConfig.xaiFallback(ModelName("grok-4.7"))._1 shouldBe 500000
    XaiConfig.xaiFallback(ModelName("grok-4.5-latest"))._1 shouldBe 500000
    XaiConfig.xaiFallback(ModelName("grok-4.3"))._1 shouldBe 1000000
    XaiConfig.xaiFallback(ModelName("grok-4.20-0309-reasoning"))._1 shouldBe 1000000
    XaiConfig.xaiFallback(ModelName("grok-4-0709"))._1 shouldBe 256000
    XaiConfig.xaiFallback(ModelName("grok-2"))._1 shouldBe 131072
    XaiConfig.xaiFallback(ModelName("grok-2"))._2 shouldBe 8192
  }

  "XaiConfig.toString" should "redact the API key" in {
    val rendered =
      XaiConfig(
        Secret("xai-secret-key-123456"),
        ModelName("grok-4.7"),
        XaiConfig.DEFAULT_BASE_URL,
        500000,
        8192
      ).toString
    (rendered should not).include("xai-secret-key-123456")
    rendered should include("grok-4.7")
  }

  "A provider = \"xai\" section" should "load with the default base URL and its own key" in {
    val hocon =
      """llm4s.providers {
        |  grok {
        |    provider = "xai"
        |    model    = "grok-4.7"
        |    apiKey   = "xai-key"
        |  }
        |}""".stripMargin
    Llm4sConfig.provider(ConfigSource.string(hocon), ProviderName("grok")).value match {
      case xai: XaiConfig =>
        xai.baseUrl shouldBe XaiConfig.DEFAULT_BASE_URL
        xai.apiKey.reveal shouldBe "xai-key"
        xai.model.asString shouldBe "grok-4.7"
      case other => fail(s"Expected XaiConfig, got $other")
    }
  }
}
