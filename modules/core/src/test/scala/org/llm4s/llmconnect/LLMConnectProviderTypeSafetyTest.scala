package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.llmconnect.config._
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }

/**
 * `LLMConnect` routes each config to its own provider's client, and refuses a mismatch.
 *
 * The OpenAI and Azure cases moved to `llm4s-openai`'s `OpenAIRoutingTest` with the
 * providers (#1132), and the DeepSeek, Z.ai and OpenRouter cases to `llm4s-openai-compatible`'s
 * `OpenAICompatibleRoutingTest`.
 */
class LLMConnectProviderTypeSafetyTest extends AnyFunSuite with Matchers {
  private given ModelRegistryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

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
}
