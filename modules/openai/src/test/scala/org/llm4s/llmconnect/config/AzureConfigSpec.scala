package org.llm4s.llmconnect.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `AzureConfig`: construction, fallbacks, redaction and self-description.
 *
 * Gathered from five core specs - `ProviderConfigSpec`, `ProviderConfigFallbackSpec`,
 * `ConfigRedactionSpec`, `ProviderConfigDescriptionSpec` and `ProviderConfigLoaderTest` -
 * when the config moved to `llm4s-openai` (#1132), plus the Azure rows of core's
 * `fromValues` blank-field checks (#1131).
 */
class AzureConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val azureEndpoint = "https://azure.example.com"
  private val azureVersion  = "2024-02-15"
  private val apiKey        = "sk-test"

  private val azure = AzureConfig("https://x.openai.azure.com", "k", "gpt-4o", "2025-01-01-preview", 128000, 4096)

  // ---- fromValues (from ProviderConfigSpec and ProviderConfigLoaderTest) ----

  test("AzureConfig.fromValues creates config with correct model") {
    val config = AzureConfig
      .fromValues(
        modelName = "gpt-4o",
        endpoint = "https://my-resource.openai.azure.com",
        apiKey = "test-key",
        apiVersion = "2024-02-15-preview"
      )
      .value

    config.model shouldBe "gpt-4o"
    config.endpoint shouldBe "https://my-resource.openai.azure.com"
    config.apiVersion shouldBe "2024-02-15-preview"
  }

  test("AzureConfig.fromValues fails for empty endpoint") {
    AzureConfig
      .fromValues(
        modelName = "gpt-4o",
        endpoint = "",
        apiKey = "test-key",
        apiVersion = "2024-02-15-preview"
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("AzureConfig.fromValues accepts the default API version") {
    val cfg = AzureConfig
      .fromValues(
        modelName = "gpt-4o",
        endpoint = "https://example.azure.com",
        apiKey = "test-key",
        apiVersion = AzureConfig.DEFAULT_API_VERSION
      )
      .value
    cfg.endpoint shouldBe "https://example.azure.com"
    cfg.apiKey shouldBe "test-key"
    cfg.apiVersion shouldBe "V2025_01_01_PREVIEW"
  }

  test("AzureConfig.fromValues returns Left when endpoint missing, whatever the API version") {
    AzureConfig.fromValues("gpt-4o", "", "test-key", AzureConfig.DEFAULT_API_VERSION).isLeft shouldBe true
  }

  // ---- blank fields (from ProviderConfigSpec) ----

  test("AzureConfig.fromValues names the blank field") {
    val blanks = Seq(
      "Azure endpoint" -> AzureConfig.fromValues("gpt-4o", " ", "key", "2024-02-15"),
      "Azure apiKey"   -> AzureConfig.fromValues("gpt-4o", "https://azure.example", " ", "2024-02-15")
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }

  // ---- fallbacks (from ProviderConfigFallbackSpec; "patch-cov-" names miss the registry) ----

  test("AzureConfig fallback returns 128000 for gpt-4o-like model") {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4o", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  test("AzureConfig fallback returns 128000 for gpt-4-turbo-like model") {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4-turbo", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  test("AzureConfig fallback returns 8192 for gpt-4-like model") {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-4", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  test("AzureConfig fallback returns 16384 for gpt-3.5-turbo-like model") {
    val cfg = AzureConfig.fromValues("patch-cov-gpt-3.5-turbo", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 16384
    cfg.reserveCompletion shouldBe 4096
  }

  test("AzureConfig fallback returns 128000 for o1-like model") {
    val cfg = AzureConfig.fromValues("patch-cov-o1-mini", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 128000
    cfg.reserveCompletion shouldBe 4096
  }

  test("AzureConfig fallback returns 8192 for unknown model") {
    val cfg = AzureConfig.fromValues("patch-cov-unknown", azureEndpoint, apiKey, azureVersion).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  // ---- redaction (from ConfigRedactionSpec) ----

  test("AzureConfig.toString does not leak the apiKey") {
    val secret = "SECRET_TEST_VALUE_12345"
    val cfg = AzureConfig
      .fromValues(
        modelName = "gpt-4",
        endpoint = "https://example.invalid",
        apiKey = secret,
        apiVersion = "2024-02-01"
      )
      .value
    (cfg.toString should not).include(secret)
    cfg.toString should include("***")
  }

  // ---- self-description (from ProviderConfigDescriptionSpec) ----

  test("AzureConfig names its provider and reports its endpoint, not a baseUrl") {
    azure.providerId shouldBe ProviderId("azure")
    // Azure's endpoint field, not a baseUrl - the distinction the old match existed to make.
    azure.endpointUrl shouldBe Some("https://x.openai.azure.com")
  }

  test("AzureConfig.withModel changes only the model") {
    val renamed = azure.withModel("m")
    renamed.model shouldBe "m"
    renamed.apiVersion shouldBe azure.apiVersion
    renamed.endpointUrl shouldBe azure.endpointUrl
    renamed.contextWindow shouldBe azure.contextWindow
  }
}
