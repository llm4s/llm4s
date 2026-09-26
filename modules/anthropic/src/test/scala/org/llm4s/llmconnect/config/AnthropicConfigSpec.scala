package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `AnthropicConfig`: construction, fallbacks, redaction and self-description.
 *
 * Gathered from five core specs - `ProviderConfigSpec`, `ProviderConfigFallbackSpec`,
 * `ConfigRedactionSpec`, `ProviderConfigDescriptionSpec` and `ProviderConfigLoaderTest` -
 * when the config moved to `llm4s-anthropic` (#1132), plus the Anthropic cases of core's
 * `fromValues` blank-field checks (#1131).
 */
class AnthropicConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val apiKey  = "sk-test"
  private val baseUrl = "https://api.example.com"

  private val anthropic = AnthropicConfig("k", "claude-sonnet-4-5", "https://api.anthropic.com", 200000, 4096)

  // ---- fromValues (from ProviderConfigSpec) ----

  test("AnthropicConfig.fromValues creates config with correct model") {
    val config = AnthropicConfig
      .fromValues(
        modelName = "claude-3-sonnet-20240229",
        apiKey = "test-key",
        baseUrl = "https://api.anthropic.com"
      )
      .value

    config.model shouldBe "claude-3-sonnet-20240229"
    config.apiKey shouldBe "test-key"
  }

  test("AnthropicConfig.fromValues sets large context window for claude-3") {
    val config = AnthropicConfig
      .fromValues(
        modelName = "claude-3-opus-20240229",
        apiKey = "test-key",
        baseUrl = "https://api.anthropic.com"
      )
      .value

    config.contextWindow shouldBe 200000
  }

  test("AnthropicConfig.fromValues keeps DEFAULT_BASE_URL as given") {
    AnthropicConfig.fromValues("claude-3-sonnet", apiKey, AnthropicConfig.DEFAULT_BASE_URL).value.baseUrl shouldBe
      "https://api.anthropic.com"
  }

  // ---- blank fields (from ProviderConfigSpec and ProviderConfigLoaderTest) ----

  test("AnthropicConfig.fromValues fails for empty apiKey") {
    AnthropicConfig
      .fromValues(
        modelName = "claude-3-sonnet",
        apiKey = "",
        baseUrl = "https://api.anthropic.com"
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("AnthropicConfig.fromValues reports the first blank field when several are blank") {
    AnthropicConfig.fromValues("claude-3", "", "").left.value.message shouldBe "Anthropic apiKey must be non-empty"
  }

  test("AnthropicConfig.fromValues returns a Left for a blank baseUrl") {
    AnthropicConfig.fromValues("claude-3", "key", " ").left.value.message shouldBe
      "Anthropic baseUrl must be non-empty"
  }

  // ---- fallbacks (from ProviderConfigFallbackSpec) ----
  // Model names use a "patch-cov-" prefix so the registry misses and the fallback runs.

  test("AnthropicConfig fallback returns 200000 for claude-3-like model") {
    val cfg = AnthropicConfig.fromValues("patch-cov-claude-3", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  test("AnthropicConfig fallback returns 200000 for claude-3.5-like model") {
    val cfg = AnthropicConfig.fromValues("patch-cov-claude-3.5-sonnet", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  test("AnthropicConfig fallback returns 100000 for claude-instant-like model") {
    val cfg = AnthropicConfig.fromValues("patch-cov-claude-instant", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 100000
    cfg.reserveCompletion shouldBe 4096
  }

  test("AnthropicConfig fallback returns 200000 for unknown model") {
    val cfg = AnthropicConfig.fromValues("patch-cov-unknown", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 200000
    cfg.reserveCompletion shouldBe 4096
  }

  // ---- redaction (from ConfigRedactionSpec) ----

  test("AnthropicConfig.toString redacts the api key") {
    val secret = "sk-ant-super-secret-value-123456"
    val config = AnthropicConfig
      .fromValues(
        modelName = "claude-3-5-sonnet-20241022",
        apiKey = secret,
        baseUrl = "https://example.invalid"
      )
      .value

    (config.toString should not).include(secret)
    config.toString should include("***")
  }

  // ---- self-description (from ProviderConfigDescriptionSpec) ----

  test("AnthropicConfig names its provider and the endpoint it will contact") {
    anthropic.providerId shouldBe ProviderId("anthropic")
    anthropic.endpointUrl shouldBe Some("https://api.anthropic.com")
  }

  test("AnthropicConfig.withModel changes only the model") {
    val renamed = anthropic.withModel("some-other-model")

    renamed.model shouldBe "some-other-model"
    renamed.providerId shouldBe anthropic.providerId
    renamed.getClass shouldBe anthropic.getClass
    renamed.endpointUrl shouldBe anthropic.endpointUrl
    renamed.contextWindow shouldBe anthropic.contextWindow
    renamed.reserveCompletion shouldBe anthropic.reserveCompletion
    renamed.asInstanceOf[AnthropicConfig].apiKey shouldBe anthropic.apiKey
  }
}
