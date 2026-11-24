package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Context-window fallback branches for the configs `llm4s-openai-compatible` holds - core's
 * `ProviderConfigFallbackSpec` cases, moved here with the configs (#1132).
 *
 * Uses model names not in the registry snapshot to trigger fallbackResolver path.
 * Model names use "patch-cov-" prefix to ensure registry miss.
 */
class OpenAICompatibleConfigFallbackSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  val apiKey  = "sk-test"
  val baseUrl = "https://api.example.com"

  "OpenAIConfig fallback" should "return 128000 for gpt-4o-like model" in {
    val cfg = OpenAIConfig.fromValues(ModelName("patch-cov-gpt-4o"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for unknown model" in {
    val cfg = OpenAIConfig.fromValues(ModelName("patch-cov-unknown"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  "CohereConfig fallback" should "return 128000 for any unregistered model" in {
    val cfg = CohereConfig.fromValues(ModelName("patch-cov-unknown"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  "ZaiConfig fallback" should "return 200000 for GLM-4.7-like model" in {
    val cfg = ZaiConfig.fromValues(ModelName("patch-cov-GLM-4.7"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for GLM-4.5-like model" in {
    val cfg = ZaiConfig.fromValues(ModelName("patch-cov-GLM-4.5"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for unknown model" in {
    val cfg = ZaiConfig.fromValues(ModelName("patch-cov-unknown"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  "DeepSeekConfig fallback" should "return 128000 for unregistered model (default branch)" in {
    val cfg =
      DeepSeekConfig.fromValues(ModelName("patch-cov-deepseek-reasoner"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 128000 for unknown model (default branch)" in {
    val cfg = DeepSeekConfig.fromValues(ModelName("patch-cov-unknown"), Secret(apiKey), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 8192
  }
}
