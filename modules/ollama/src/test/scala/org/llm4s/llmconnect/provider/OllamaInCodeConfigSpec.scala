package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ EmbeddingProviderConfig, OllamaConfig, ProviderConfig }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-ollama` builds its chat client and embedding provider from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class OllamaInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sOllamaModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuilds(config: ProviderConfig, provider: String): Unit =
    config.providerId shouldBe ProviderId(provider)
    val client = LLMConnect.getClient(config).value
    val window = client.getContextWindow()
    client.close()
    window shouldBe config.contextWindow

  private def assertBuildsEmbeddings(provider: String, config: EmbeddingProviderConfig): Unit =
    EmbeddingClient.from(provider, config).value.providerId shouldBe Some(ProviderId(provider))

  "llm4s-ollama, configured in code" should {
    "build an Ollama chat client from a model and base URL" in {
      assertBuilds(OllamaConfig.fromValues("llama3", "http://localhost:11434").value, "ollama")
    }

    "build an Ollama embedding client from a base URL and model" in {
      assertBuildsEmbeddings(
        "ollama",
        EmbeddingProviderConfig(baseUrl = "http://localhost:11434", model = "nomic-embed-text", apiKey = Secret(""))
      )
    }
  }
