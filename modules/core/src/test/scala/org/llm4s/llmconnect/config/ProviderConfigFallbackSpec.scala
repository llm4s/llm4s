package org.llm4s.llmconnect.config

import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests provider config fallback branches for patch coverage.
 *
 * Uses model names not in the registry snapshot to trigger fallbackResolver path.
 * Model names use "patch-cov-" prefix to ensure registry miss.
 */
class ProviderConfigFallbackSpec extends AnyFlatSpec with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  val azureEndpoint = "https://azure.example.com"
  val azureVersion  = "2024-02-15"
  val apiKey        = "sk-test"
  val baseUrl       = "https://api.example.com"

  "OpenAIConfig fallback" should "return 128000 for gpt-4o-like model" in {
    val cfg = OpenAIConfig.fromValues("patch-cov-gpt-4o", apiKey, None, baseUrl).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for unknown model" in {
    val cfg = OpenAIConfig.fromValues("patch-cov-unknown", apiKey, None, baseUrl).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  "AzureConfig fallback" should "return 128000 for gpt-4o-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4o", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for gpt-4-turbo-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4-turbo", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for gpt-4-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 16384 for gpt-3.5-turbo-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-3.5-turbo", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 16384
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for o1-like model" in {
    val cfg = AzureConfig.fromValues("patch-cov-o1-mini", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 8192 for unknown model" in {
    val cfg = AzureConfig.fromValues("patch-cov-unknown", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  "DeepSeekConfig fallback" should "return 128000 for unregistered model (default branch)" in {
    val cfg = DeepSeekConfig.fromValues("patch-cov-deepseek-reasoner", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 8192
  }

  it should "return 128000 for unknown model (default branch)" in {
    val cfg = DeepSeekConfig.fromValues("patch-cov-unknown", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 8192
  }

  "ZaiConfig fallback" should "return 200000 for GLM-4.7-like model" in {
    val cfg = ZaiConfig.fromValues("patch-cov-GLM-4.7", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for GLM-4.5-like model" in {
    val cfg = ZaiConfig.fromValues("patch-cov-GLM-4.5", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  it should "return 128000 for unknown model" in {
    val cfg = ZaiConfig.fromValues("patch-cov-unknown", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  "CohereConfig fallback" should "return 128000 for any unregistered model" in {
    val cfg = CohereConfig.fromValues("patch-cov-unknown", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }
}
