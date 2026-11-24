package org.llm4s.llmconnect.config

import org.llm4s.types.ProviderModelTypes.BaseUrl
import org.llm4s.types.ProviderModelTypes.{ ModelName, ProviderId }
import org.llm4s.error.ConfigurationError
import org.scalatest.EitherValues
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

/**
 * `OllamaConfig`'s construction, fallbacks and self-description.
 *
 * Gathered from four core specs - `ProviderConfigSpec`, `ProviderConfigFallbackSpec`,
 * `ProviderConfigLoaderTest` and `ProviderConfigDescriptionSpec` - when the config moved
 * to `llm4s-ollama` (#1132).
 */
class OllamaConfigSpec extends AnyFunSuite with Matchers with EitherValues {

  private given ContextWindowResolver =
    ContextWindowResolver(org.llm4s.model.ModelRegistryTestSupport.defaultService())

  private val baseUrl = "https://api.example.com"

  test("OllamaConfig.fromValues creates config with correct model") {
    val config = OllamaConfig
      .fromValues(
        modelName = ModelName("llama3"),
        baseUrl = BaseUrl("http://localhost:11434")
      )
      .value

    config.model.asString shouldBe "llama3"
    config.baseUrl shouldBe BaseUrl("http://localhost:11434")
  }

  test("OllamaConfig.fromValues sets correct context window for llama2") {
    val config = OllamaConfig
      .fromValues(
        modelName = ModelName("llama2"),
        baseUrl = BaseUrl("http://localhost:11434")
      )
      .value

    config.contextWindow shouldBe 4096
  }

  test("OllamaConfig.fromValues sets context window for mistral") {
    val config = OllamaConfig
      .fromValues(
        modelName = ModelName("mistral"),
        baseUrl = BaseUrl("http://localhost:11434")
      )
      .value

    // Context window may come from registry metadata or fallback logic
    config.contextWindow should be > 0
  }

  test("OllamaConfig.fromValues fails for empty baseUrl") {
    OllamaConfig
      .fromValues(
        modelName = ModelName("llama3"),
        baseUrl = BaseUrl("")
      )
      .left
      .value shouldBe a[ConfigurationError]
  }

  test("OllamaConfig.fromValues sets reserveCompletion for all models") {
    val config = OllamaConfig.fromValues(ModelName("llama3"), BaseUrl("http://localhost:11434")).value
    // reserveCompletion may come from registry metadata or fallback logic
    config.reserveCompletion should be > 0
  }

  test("OllamaConfig.load returns Left when base url missing") {
    val res = OllamaConfig.fromValues(ModelName("llama3"), BaseUrl(""))
    res.isLeft shouldBe true
  }

  // Model names use a "patch-cov-" prefix so the registry misses and the fallback resolver runs.
  test("OllamaConfig fallback: return 4096 for llama2-like model") {
    val cfg = OllamaConfig.fromValues(ModelName("patch-cov-llama2"), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 4096
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 8192 for llama3-like model") {
    val cfg = OllamaConfig.fromValues(ModelName("patch-cov-llama3"), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 16384 for codellama-like model") {
    val cfg = OllamaConfig.fromValues(ModelName("patch-cov-codellama"), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 16384
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 32768 for mistral-like model") {
    val cfg = OllamaConfig.fromValues(ModelName("patch-cov-mistral"), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 32768
    cfg.reserveCompletion shouldBe 4096
  }

  test("OllamaConfig fallback: return 8192 for unknown model") {
    val cfg = OllamaConfig.fromValues(ModelName("patch-cov-unknown"), BaseUrl(baseUrl)).value
    cfg.contextWindow shouldBe 8192
    cfg.reserveCompletion shouldBe 4096
  }

  private def described = OllamaConfig(ModelName("llama3"), BaseUrl("http://localhost:11434"), 8192, 4096)

  test("OllamaConfig describes itself as the ollama provider at its base URL") {
    described.providerId shouldBe ProviderId("ollama")
    described.endpointUrl shouldBe Some("http://localhost:11434")
  }

  test("OllamaConfig.withModel changes only the model") {
    val renamed = described.withModel(ModelName("some-other-model"))
    renamed shouldBe OllamaConfig(ModelName("some-other-model"), BaseUrl("http://localhost:11434"), 8192, 4096)
  }

  // ---- growth-prone construction (#1388) ----

  test("OllamaConfig(model, baseUrl) takes the context window from the model name") {
    OllamaConfig(ModelName("llama3"), BaseUrl("http://localhost:11434")) shouldBe described
    OllamaConfig(ModelName("codellama"), BaseUrl("http://localhost:11434")).contextWindow shouldBe 16384
  }

  test("OllamaConfig's setters each change one field") {
    val adjusted = described
      .withBaseUrl(BaseUrl("http://ollama:11434"))
      .withContextWindow(32768)
      .withReserveCompletion(2048)
    adjusted shouldBe OllamaConfig(ModelName("llama3"), BaseUrl("http://ollama:11434"), 32768, 2048)
  }

  test("OllamaConfig.apply defaults every field after baseUrl and takes timeouts") {
    val defaults = OllamaConfig(ModelName("codellama"), BaseUrl("http://ollama:11434"), contextWindow = 16384)
    defaults.contextWindow shouldBe 16384
    defaults.reserveCompletion shouldBe OllamaConfig.DEFAULT_RESERVE_COMPLETION
    defaults.timeouts shouldBe ProviderTimeouts.default

    val timeouts = ProviderTimeouts(request = Some(30.seconds))
    val timed    = OllamaConfig(ModelName("llama3"), BaseUrl("http://ollama:11434"), timeouts = timeouts)
    timed.contextWindow shouldBe OllamaConfig.DEFAULT_CONTEXT_WINDOW
    timed.reserveCompletion shouldBe 4096
    timed.timeouts shouldBe timeouts
    OllamaConfig(ModelName("llama3"), BaseUrl("http://ollama:11434")).withTimeouts(timeouts) shouldBe timed
  }
}
