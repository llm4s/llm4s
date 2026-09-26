package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.llmconnect.config._
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig

/**
 * `LLMConnect` routes each config to its own provider's client, and refuses a mismatch.
 *
 * The OpenAI and Azure cases moved to `llm4s-openai`'s `OpenAIRoutingTest` with the
 * providers (#1132), and the DeepSeek case to `llm4s-openai-compatible`'s
 * `OpenAICompatibleRoutingTest`.
 */
class LLMConnectProviderTypeSafetyTest extends AnyFunSuite with Matchers {
  private given ModelRegistryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

  test("OpenRouter provider with OpenAIConfig returns OpenRouterClient") {
    val cfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "openrouter/test-model",
      organization = None,
      baseUrl = "https://openrouter.ai/api/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(ProviderId("openrouter"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "OpenRouterClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Zai provider with ZaiConfig returns ZaiClient") {
    val cfg: ProviderConfig = ZaiConfig(
      apiKey = "key",
      model = "GLM-4.7",
      baseUrl = "https://api.z.ai/api/paas/v4",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(ProviderId("zai"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "ZaiClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Cohere provider with CohereConfig returns CohereClient") {
    val cfg: ProviderConfig = CohereConfig(
      apiKey = "key",
      model = "command-r",
      baseUrl = "https://example.invalid",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(ProviderId("cohere"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "CohereClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("Mistral provider with MistralConfig returns MistralClient") {
    val cfg: ProviderConfig = MistralConfig(
      apiKey = "key",
      model = "mistral-small-latest",
      baseUrl = "https://example.invalid",
      contextWindow = 128000,
      reserveCompletion = 4096
    )
    val res = LLMConnect.getClient(ProviderId("mistral"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "MistralClient"
      case Left(err)     => fail(s"Expected Right, got Left($err)")
    }
  }

  test("OpenRouter provider with non-OpenAIConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = FixtureChatConfig(apiKey = "key", model = "fixture-model")

    val res = LLMConnect.getClient(ProviderId("openrouter"), wrongCfg)
    res.isLeft shouldBe true
  }

  test("Zai provider with non-ZaiConfig should throw IllegalArgumentException") {
    val wrongCfg: ProviderConfig = OpenAIConfig(
      apiKey = "key",
      model = "gpt-4o",
      organization = None,
      baseUrl = "https://api.openai.com/v1",
      contextWindow = 128000,
      reserveCompletion = 4096
    )

    val res = LLMConnect.getClient(ProviderId("zai"), wrongCfg)
    res.isLeft shouldBe true
  }
}
