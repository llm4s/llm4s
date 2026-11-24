package org.llm4s.config

import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId, ProviderName }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.llmconnect.spi.{
  ProviderBuildContext,
  ProviderConfigContext,
  ProviderConfigSpec,
  ProviderDescriptor,
  ProviderRegistry
}
import org.llm4s.testutil.FixtureChatProvider
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * A section's `timeouts` or `http` block that its provider's config does not take is refused when the section
 * is loaded (#2805): it used to be dropped without a word, so a configured proxy was silently bypassed.
 */
class UntakenSectionSettingsSpec extends AnyWordSpec with Matchers with EitherValues:

  /** A config that carries neither timeouts nor network settings, as an in-process provider's does. */
  final private case class InProcessConfig(providerId: ProviderId) extends org.llm4s.testutil.FixedProviderSettings:
    val model: ModelName                            = ModelName("m")
    val contextWindow: Int                          = 8192
    val reserveCompletion: Int                      = 1024
    val endpointUrl: Option[BaseUrl]                = None
    def withModel(model: ModelName): ProviderConfig = this

  private object InProcess extends ProviderDescriptor:
    val id: ProviderId                 = ProviderId("inprocess")
    val configSpec: ProviderConfigSpec = ProviderConfigSpec(defaultBaseUrl = Some(FixtureChatProvider.DefaultBaseUrl))
    def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
      Right(InProcessConfig(id))
    def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
      FixtureChatProvider.build(config, context)

  private given ProviderRegistry = ProviderRegistry.of(FixtureChatProvider, InProcess)

  private def load(hocon: String): Result[ProviderConfig] =
    Llm4sConfig.provider(ConfigSource.string(hocon), ProviderName("main"))

  "a section whose provider's config does not take a setting" should {
    "be refused when it sets timeouts" in {
      val error = load("llm4s.providers.main { provider = inprocess, model = m, timeouts { request = 3m } }").left.value
      error.message should include("llm4s.providers.main.timeouts")
      error.message should include("inprocess")
    }

    "be refused when it has an http block of its own" in {
      val error = load(
        """llm4s.providers.main { provider = inprocess, model = m, http { proxy { url = "http://proxy.test:3128" } } }"""
      ).left.value
      error.message should include("llm4s.providers.main.http")
      error.message should include("inprocess")
    }

    "load when it sets neither, even with a process-wide llm4s.http block that has nothing to route" in {
      load(
        """llm4s.http { proxy { url = "http://proxy.test:3128" } }
          |llm4s.providers.main { provider = inprocess, model = m }
          |""".stripMargin
      ).value shouldBe a[ProviderConfig]
    }
  }

  "a section whose provider's config takes both" should {
    "load with them" in {
      load(
        """llm4s.providers.main {
          |  provider = fixturechat, model = m, apiKey = k
          |  timeouts { request = 3m }
          |  http { proxy { url = "http://proxy.test:3128" } }
          |}
          |""".stripMargin
      ).isRight shouldBe true
    }
  }
