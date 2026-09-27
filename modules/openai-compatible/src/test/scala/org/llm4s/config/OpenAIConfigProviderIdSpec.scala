package org.llm4s.config

import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig }
import org.llm4s.llmconnect.provider.{ OpenRouterClient, OpenRouterProvider }
import org.llm4s.llmconnect.{ LLMConnect, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pureconfig.ConfigSource

/**
 * Which provider an `OpenAIConfig` reports (#1132 follow-up).
 *
 * OpenAI, OpenRouter and Requesty share `OpenAIConfig`, and `LLMConnect` routes a config by its
 * `providerId`. That id used to be inferred from the base URL alone, so a Requesty config
 * reported `openai`, and an OpenRouter section pointed at a proxy was routed to OpenAI. A
 * descriptor now sets the id; a config built without one still infers it.
 */
class OpenAIConfigProviderIdSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ModelRegistryService  = org.llm4s.model.ModelRegistryTestSupport.defaultService()
  private given ContextWindowResolver = ContextWindowResolver(summon[ModelRegistryService])

  private def config(baseUrl: String, providerId: Option[ProviderId] = None) =
    OpenAIConfig.fromValues("gpt-4o-mini", "sk-test", None, baseUrl, providerId).value

  "OpenAIConfig.providerId" should {

    "infer openai, or openrouter from an openrouter.ai base URL, when none is set" in {
      config("https://api.openai.com/v1").providerId shouldBe ProviderId("openai")
      config("https://openrouter.ai/api/v1").providerId shouldBe ProviderId("openrouter")
      config("https://router.requesty.ai/v1").providerId shouldBe ProviderId("openai")
    }

    "report the id it was given, whatever the base URL" in {
      config("https://router.requesty.ai/v1", Some(ProviderId("requesty"))).providerId shouldBe ProviderId("requesty")
      config("https://llm-proxy.internal/v1", Some(ProviderId("openrouter"))).providerId shouldBe
        ProviderId("openrouter")
    }

    "keep the id through withModel, and show it in toString" in {
      val requesty = config("https://router.requesty.ai/v1", Some(ProviderId("requesty")))
      requesty.withModel("other").providerId shouldBe ProviderId("requesty")
      requesty.toString should include("providerId=requesty")
    }

    "default to None when built with the primary constructor" in {
      OpenAIConfig("k", "m", None, "https://api.openai.com/v1", 8192, 4096).explicitProviderId shouldBe None
    }
  }

  "a provider = \"openrouter\" section" should {

    val hocon =
      """
        |llm4s.providers {
        |  or-proxy {
        |    provider = "openrouter"
        |    model = "openai/gpt-4o-mini"
        |    apiKey = "or-key"
        |    baseUrl = "https://llm-proxy.internal/openrouter/v1"
        |  }
        |}
        |""".stripMargin

    "report openrouter even with a base URL that is not openrouter.ai's" in {
      Llm4sConfig.provider(ConfigSource.string(hocon), "or-proxy").value.providerId shouldBe ProviderId("openrouter")
    }

    "reach the OpenRouter client through LLMConnect" in {
      val cfg = Llm4sConfig.provider(ConfigSource.string(hocon), "or-proxy").value
      LLMConnect.getClient(cfg).value shouldBe an[OpenRouterClient]
      OpenRouterProvider.buildClient(cfg, LlmClientOptions()).value shouldBe an[OpenRouterClient]
    }
  }
