package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-jina` builds its embedding provider from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class JinaInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sJinaModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuildsEmbeddings(provider: String, config: EmbeddingProviderConfig): Unit =
    EmbeddingClient.from(provider, config).value.providerId shouldBe Some(ProviderId(provider))

  "llm4s-jina, configured in code" should {
    "build a Jina embedding client from a base URL, model and key" in {
      assertBuildsEmbeddings(
        "jina",
        EmbeddingProviderConfig(
          baseUrl = "https://api.jina.ai",
          model = "jina-embeddings-v3",
          apiKey = Secret("jina-test")
        )
      )
    }
  }
