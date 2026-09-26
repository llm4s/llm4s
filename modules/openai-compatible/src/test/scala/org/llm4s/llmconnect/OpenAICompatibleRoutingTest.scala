package org.llm4s.llmconnect

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.llmconnect.config._
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.testutil.FixtureChatConfig

/**
 * `LLMConnect` routes each `llm4s-openai-compatible` config to its own client, and refuses a
 * mismatch. The DeepSeek, Z.ai and OpenRouter cases are core's routing cases
 * (`LLMConnectProviderTypeSafetyTest`, `LLMConnectResultTest`,
 * `LLMConnectEnvReaderRoutingTest`), moved here with the providers (#1132).
 */
class OpenAICompatibleRoutingTest extends AnyFunSuite with Matchers {
  private given ModelRegistryService = ModelRegistryService.fromConfig(ModelRegistryConfig.default).toOption.get

  test("DeepSeek provider with DeepSeekConfig returns DeepSeekClient") {
    val cfg: ProviderConfig = DeepSeekConfig(
      apiKey = "key",
      model = "deepseek-chat",
      baseUrl = "https://example.invalid/v1",
      contextWindow = 128000,
      reserveCompletion = 8192
    )
    val res = LLMConnect.getClient(ProviderId("deepseek"), cfg)
    res match {
      case Right(client) => client.getClass.getSimpleName shouldBe "DeepSeekClient"
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

  test("openai-compatible provider with OpenAICompatibleConfig returns OpenAICompatibleClient") {
    val cfg: ProviderConfig = OpenAICompatibleConfig(model = "m", baseUrl = "http://localhost:8000/v1")
    LLMConnect.getClient(cfg).map(_.getClass.getSimpleName) shouldBe Right("OpenAICompatibleClient")
    LLMConnect.getClient(ProviderId("openai-compatible"), cfg).map(_.getContextWindow()) shouldBe Right(
      OpenAICompatibleConfig.DEFAULT_CONTEXT_WINDOW
    )
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

  test("openai-compatible provider refuses another provider's config") {
    val wrongCfg: ProviderConfig = FixtureChatConfig(apiKey = "key", model = "fixture-model")
    LLMConnect.getClient(ProviderId("openai-compatible"), wrongCfg).isLeft shouldBe true
  }
}
