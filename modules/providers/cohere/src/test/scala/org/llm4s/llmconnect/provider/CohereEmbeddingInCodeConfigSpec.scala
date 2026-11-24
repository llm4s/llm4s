package org.llm4s.llmconnect.provider

import org.llm4s.types.ProviderModelTypes.{ BaseUrl, ModelName, ProviderId }
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-cohere` builds its embedding provider from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class CohereEmbeddingInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sCohereModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuildsEmbeddings(provider: String, config: EmbeddingProviderConfig): Unit =
    EmbeddingClient.from(config).value.providerId shouldBe Some(ProviderId(provider))

  "llm4s-cohere, configured in code" should {
    "build a Cohere embedding client from a base URL, model and key" in {
      assertBuildsEmbeddings(
        "cohere",
        EmbeddingProviderConfig(
          providerId = ProviderId("cohere"),
          baseUrl = BaseUrl("https://api.cohere.com"),
          model = ModelName("embed-english-v3.0"),
          apiKey = Some(Secret("co-test"))
        )
      )
    }
  }
