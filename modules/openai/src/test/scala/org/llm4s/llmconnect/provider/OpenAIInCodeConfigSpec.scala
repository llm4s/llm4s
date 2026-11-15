package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.config.{ AzureConfig, EmbeddingProviderConfig, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.{ EmbeddingClient, LLMConnect }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.security.Secret
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `llm4s-openai` builds its chat clients and embedding provider from values alone (#2340): `fromValues` (or `EmbeddingProviderConfig.apply`), the
 * bundled model catalogue from `ModelRegistryService.default()` and the module registered explicitly. Nothing reads
 * an `application.conf`, a `reference.conf` key or an environment variable, as for an application whose
 * configuration comes from ciris, zio-config or its own code. See docs/getting-started/configuration-in-code.md.
 */
class OpenAIInCodeConfigSpec extends AnyWordSpec with Matchers with EitherValues:

  private given ProviderRegistry     = ProviderRegistry.ofModules(new Llm4sOpenAIModule)
  private given ModelRegistryService = ModelRegistryService.default().value

  private def assertBuilds(config: ProviderConfig, provider: String): Unit =
    config.providerId shouldBe ProviderId(provider)
    val client = LLMConnect.getClient(config).value
    val window = client.getContextWindow()
    client.close()
    window shouldBe config.contextWindow

  private def assertBuildsEmbeddings(provider: String, config: EmbeddingProviderConfig): Unit =
    EmbeddingClient.from(provider, config).value.providerId shouldBe Some(ProviderId(provider))

  "llm4s-openai, configured in code" should {
    "build an OpenAI client from a model, key and base URL" in {
      assertBuilds(
        OpenAIConfig.fromValues("gpt-4o-mini", Secret("sk-test"), OpenAIConfig.DEFAULT_BASE_URL).value,
        "openai"
      )
    }

    "build an Azure OpenAI client from a deployment, key and endpoint" in {
      assertBuilds(
        AzureConfig.fromValues("gpt-4o", Secret("azure-test"), "https://my-resource.openai.azure.com").value,
        "azure"
      )
    }

    "build a Requesty client from an OpenAI config that names it" in {
      val config = OpenAIConfig
        .fromValues(
          "openai/gpt-4o-mini",
          Secret("rq-test"),
          RequestyProvider.DEFAULT_BASE_URL,
          providerId = Some(ProviderId("requesty"))
        )
        .value
      assertBuilds(config, "requesty")
    }

    "build an OpenAI embedding client from a base URL, model and key" in {
      assertBuildsEmbeddings(
        "openai",
        EmbeddingProviderConfig(
          baseUrl = OpenAIConfig.DEFAULT_BASE_URL,
          model = "text-embedding-3-small",
          apiKey = Secret("sk-test")
        )
      )
    }
  }
