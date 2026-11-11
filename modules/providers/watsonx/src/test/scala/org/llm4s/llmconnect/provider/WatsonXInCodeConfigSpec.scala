package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ ProviderConfig, WatsonXConfig }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-watsonx` builds its chat client from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class WatsonXInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sWatsonXModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuilds(config: ProviderConfig, provider: String): Unit =
    config.providerId shouldBe ProviderId(provider)
    val client = LLMConnect.getClient(config).value
    val window = client.getContextWindow()
    client.close()
    window shouldBe config.contextWindow

  "llm4s-watsonx, configured in code" should {
    "build a watsonx.ai client from a model, key and project" in {
      assertBuilds(
        WatsonXConfig.fromValues("ibm/granite-3-8b-instruct", Secret("wx-test"), projectId = Some("my-project")).value,
        "watsonx"
      )
    }
  }
