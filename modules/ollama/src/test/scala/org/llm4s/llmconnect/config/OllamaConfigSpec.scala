package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Try

/**
 * `OllamaConfig`'s construction, fallbacks and self-description.
 *
 * Gathered from four core specs - `ProviderConfigSpec`, `ProviderConfigFallbackSpec`,
 * `ProviderConfigLoaderTest` and `ProviderConfigDescriptionSpec` - when the config moved
 * to `llm4s-ollama` (#1132).
 */
class OllamaConfigSpec extends AnyFunSuite with Matchers {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val baseUrl = "https://api.example.com"

  test("OllamaConfig.fromValues creates config with correct model") {
    val config = OllamaConfig.fromValues(
      modelName = "llama3",
      baseUrl = "http://localhost:11434"
    )

    config.model shouldBe "llama3"
    config.baseUrl shouldBe "http://localhost:11434"
  }

  test("OllamaConfig.fromValues sets correct context window for llama2") {
    val config = OllamaConfig.fromValues(
      modelName = "llama2",
      baseUrl = "http://localhost:11434"
    )

    config.contextWindow shouldBe 4096
  }

  test("OllamaConfig.fromValues sets context window for mistral") {
    val config = OllamaConfig.fromValues(
      modelName = "mistral",
      baseUrl = "http://localhost:11434"
    )

    // Context window may come from registry metadata or fallback logic
    config.contextWindow should be > 0
  }

  test("OllamaConfig.fromValues throws for empty baseUrl") {
    an[IllegalArgumentException] should be thrownBy {
      OllamaConfig.fromValues(
        modelName = "llama3",
        baseUrl = ""
      )
    }
  }

  test("OllamaConfig.fromValues sets reserveCompletion for all models") {
    val config = OllamaConfig.fromValues("llama3", "http://localhost:11434")
    // reserveCompletion may come from registry metadata or fallback logic
    config.reserveCompletion should be > 0
  }

  test("OllamaConfig.load returns Left when base url missing") {
    val res = Try(OllamaConfig.fromValues("llama3", "")).toEither
    res.isLeft shouldBe true
  }

  // Model names use a "patch-cov-" prefix so the registry misses and the fallback resolver runs.
  test("OllamaConfig fallback: return 4096 for llama2-like model") {
    val cfg = OllamaConfig.fromValues("patch-cov-llama2", baseUrl)
    cfg.contextWindow shouldBe 4096
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 8192 for llama3-like model") {
    val cfg = OllamaConfig.fromValues("patch-cov-llama3", baseUrl)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 16384 for codellama-like model") {
    val cfg = OllamaConfig.fromValues("patch-cov-codellama", baseUrl)
    cfg.contextWindow shouldBe 16384
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 32768 for mistral-like model") {
    val cfg = OllamaConfig.fromValues("patch-cov-mistral", baseUrl)
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 8192 for unknown model") {
    val cfg = OllamaConfig.fromValues("patch-cov-unknown", baseUrl)
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  private def described = OllamaConfig("llama3", "http://localhost:11434", 8192, 4096)

  test("OllamaConfig describes itself as the ollama provider at its base URL") {
    described.providerId shouldBe ProviderId("ollama")
    described.endpointUrl shouldBe Some("http://localhost:11434")
  }

  test("OllamaConfig.withModel changes only the model") {
    val renamed = described.withModel("some-other-model")
    renamed shouldBe described.copy(model = "some-other-model")
  }
}
