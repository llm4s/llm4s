package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

/**
 * `GeminiConfig` and `VertexAIConfig`: construction, fallbacks, redaction and
 * self-description.
 *
 * Gathered from four core specs - `ProviderConfigSpec`, `ProviderConfigFallbackSpec`,
 * `ConfigRedactionSpec` and `ProviderConfigDescriptionSpec` - when the configs moved to
 * `llm4s-gemini` (#1132), plus the Gemini and Vertex AI cases of core's `fromValues`
 * blank-field checks (#1131).
 */
class GeminiConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val apiKey  = "sk-test"
  private val baseUrl = "https://api.example.com"

  private val gemini   = GeminiConfig("k", "gemini-2.0-flash", "https://x.invalid/v1beta", 1048576, 8192)
  private val vertexai = VertexAIConfig("proj", "us-central1", "gemini-2.0-flash", None, 1048576, 8192)

  // ---- GeminiConfig.fromValues: base URL normalisation (from ProviderConfigSpec) ----

  test("GeminiConfig.fromValues appends v1beta when baseUrl is the API host root") {
    val config = GeminiConfig
      .fromValues(
        modelName = "gemini-1.5-flash",
        apiKey = "test-key",
        baseUrl = "https://generativelanguage.googleapis.com"
      )
      .value

    config.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
  }

  test("GeminiConfig.fromValues preserves explicit versioned baseUrl") {
    val config = GeminiConfig
      .fromValues(
        modelName = "gemini-1.5-flash",
        apiKey = "test-key",
        baseUrl = "https://generativelanguage.googleapis.com/v1beta"
      )
      .value

    config.baseUrl shouldBe "https://generativelanguage.googleapis.com/v1beta"
  }

  test("GeminiConfig.DEFAULT_BASE_URL is already versioned, so fromValues keeps it as is") {
    GeminiConfig.fromValues("gemini-2.0-flash", apiKey, GeminiConfig.DEFAULT_BASE_URL).value.baseUrl shouldBe
      GeminiConfig.DEFAULT_BASE_URL
  }

  // ---- GeminiConfig fallbacks (from ProviderConfigFallbackSpec) ----
  // Model names use a "patch-cov-" prefix so the registry misses and the fallback runs.

  test("GeminiConfig fallback returns 1048576 for gemini-2-like model") {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-2", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  test("GeminiConfig fallback returns 1048576 for gemini-1.5-like model") {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-1.5-pro", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  test("GeminiConfig fallback returns 32768 for gemini-1.0-like model") {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-1.0", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 8192
  }

  test("GeminiConfig fallback returns 1048576 for gemini-pro-like model") {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-pro", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  test("GeminiConfig fallback returns 1048576 for gemini-flash-like model") {
    val cfg = GeminiConfig.fromValues("patch-cov-gemini-flash", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  test("GeminiConfig fallback returns 1048576 for unknown model") {
    val cfg = GeminiConfig.fromValues("patch-cov-unknown", apiKey, baseUrl).value
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  test("VertexAIConfig fallback returns the Gemini defaults for an unknown model") {
    val cfg = VertexAIConfig.fromValues("patch-cov-unknown", projectId = "my-project").value
    cfg.location shouldBe VertexAIConfig.DEFAULT_LOCATION
    cfg.credentialFilePath shouldBe None
    cfg.contextWindow shouldBe 1048576
    cfg.reserveCompletion shouldBe 8192
  }

  // ---- redaction (from ConfigRedactionSpec) ----

  test("GeminiConfig.toString does not leak the apiKey") {
    val secret = "SECRET_TEST_VALUE_12345"
    val gemini = GeminiConfig
      .fromValues(
        modelName = "gemini-1.5-pro",
        apiKey = secret,
        baseUrl = "https://example.invalid"
      )
      .value
    (gemini.toString should not).include(secret)
    gemini.toString should include("***")
  }

  test("VertexAIConfig.toString does not leak the credential file path") {
    val vertex = VertexAIConfig("proj", "us-central1", "gemini-2.0-flash", Some("/secret/creds.json"), 1048576, 8192)
    (vertex.toString should not).include("/secret/creds.json")
    vertex.toString should include("credentialFilePath=<redacted>")
  }

  // ---- self-description (from ProviderConfigDescriptionSpec) ----

  test("providerId names each provider in its canonical spelling") {
    gemini.providerId shouldBe ProviderId("gemini")
    vertexai.providerId shouldBe ProviderId("vertexai")
  }

  test("endpointUrl returns the URL the config will actually contact") {
    gemini.endpointUrl shouldBe Some("https://x.invalid/v1beta")
    // Vertex derives its URL from the location; the old ConfigPolicy match returned None here.
    vertexai.endpointUrl shouldBe Some("https://us-central1-aiplatform.googleapis.com/v1")
  }

  test("withModel changes only the model, preserving type, provider and provider-specific fields") {
    Seq[ProviderConfig](gemini, vertexai).foreach { config =>
      val renamed = config.withModel("some-other-model")
      renamed.model shouldBe "some-other-model"
      renamed.providerId shouldBe config.providerId
      renamed.getClass shouldBe config.getClass
      renamed.endpointUrl shouldBe config.endpointUrl
      renamed.contextWindow shouldBe config.contextWindow
      renamed.reserveCompletion shouldBe config.reserveCompletion
    }
    vertexai.withModel("m").asInstanceOf[VertexAIConfig].projectId shouldBe vertexai.projectId
    gemini.withModel("m").asInstanceOf[GeminiConfig].apiKey shouldBe gemini.apiKey
  }

  // ---- fromValues returns Left for a blank required field (from ProviderConfigSpec) ----

  test("fromValues returns a Left naming the provider and field for a blank required field") {
    val blanks: Seq[(String, Either[org.llm4s.error.LLMError, ProviderConfig])] = Seq(
      "Gemini apiKey"       -> GeminiConfig.fromValues("gemini-2.0-flash", " ", "https://example.invalid"),
      "Gemini baseUrl"      -> GeminiConfig.fromValues("gemini-2.0-flash", "key", " "),
      "Vertex AI projectId" -> VertexAIConfig.fromValues("gemini-2.0-flash", projectId = " "),
      "Vertex AI location"  -> VertexAIConfig.fromValues("gemini-2.0-flash", projectId = "p", location = " ")
    )

    blanks.foreach { case (field, result) =>
      withClue(field) {
        result.left.value.message shouldBe s"$field must be non-empty"
      }
    }
  }

  test("VertexAIConfig.fromValues builds a config for a non-blank project and location") {
    val config = VertexAIConfig.fromValues("gemini-2.0-flash", projectId = "my-project").value

    config.projectId shouldBe "my-project"
    config.location shouldBe VertexAIConfig.DEFAULT_LOCATION
  }
}
