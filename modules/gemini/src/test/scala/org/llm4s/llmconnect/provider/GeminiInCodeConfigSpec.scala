package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ GeminiConfig, ProviderConfig, VertexAIConfig }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-gemini` builds its chat clients from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class GeminiInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sGeminiModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuilds(config: ProviderConfig, provider: String): Unit =
    config.providerId shouldBe ProviderId(provider)
    val client = LLMConnect.getClient(config).value
    val window = client.getContextWindow()
    client.close()
    window shouldBe config.contextWindow

  "llm4s-gemini, configured in code" should {
    "build a Gemini API client from a model, key and base URL" in {
      assertBuilds(
        GeminiConfig.fromValues("gemini-2.0-flash", Secret("google-test"), GeminiConfig.DEFAULT_BASE_URL).value,
        "gemini"
      )
    }

    "build a Vertex AI client from a model and project" in {
      assertBuilds(VertexAIConfig.fromValues("gemini-2.0-flash", "my-gcp-project").value, "vertexai")
    }
  }
