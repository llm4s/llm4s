package org.llm4s.llmconnect.provider

import org.llm4s.config.DefaultConfig
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for Azure OpenAI deployments.
 *
 * Azure reuses `OpenAIClient` but not `OpenAIConfig`: its endpoint is
 * per-deployment and it requires an `apiVersion`, which `AzureConfig` carries.
 */
object AzureProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("azure")

  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    requiresEndpoint = true,
    endpointDescription = "the model endpoint/deployment name in your Azure OpenAI resource"
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      endpoint <- ProviderDescriptor.requireField(
        providerName,
        "endpoint",
        section.endpoint,
        "llm4s.providers.<name>.endpoint"
      )
      apiKey <- ProviderDescriptor.requireApiKey(providerName, section)
      apiVersion = section.apiVersion.getOrElse(DefaultConfig.DEFAULT_AZURE_V2025_01_01_PREVIEW)
      config <- AzureConfig.fromValues(section.model.asString, endpoint, apiKey, apiVersion)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[AzureConfig](id, config)
      .flatMap(OpenAIClient(_, options.metrics, options.exchangeLogging))
