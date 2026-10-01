package org.llm4s.llmconnect.provider

import org.llm4s.config.OpenAIConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ AzureConfig, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigKey, ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for Azure OpenAI deployments.
 *
 * Azure reuses `OpenAIClient` but not `OpenAIConfig`: its endpoint is
 * per-deployment and it requires an `apiVersion`, which `AzureConfig` carries.
 *
 * Both are provider-specific keys of the section - `endpoint` (required) and `apiVersion`
 * (default [[org.llm4s.llmconnect.config.AzureConfig.DEFAULT_API_VERSION]]) - rather than fields of `NamedProviderConfig`,
 * which they were until [[https://github.com/llm4s/llm4s/issues/1133 #1133]]; the HOCON is
 * unchanged:
 *
 * {{{
 * azure-main {
 *   provider   = "azure"
 *   model      = "gpt-4o"
 *   endpoint   = "https://my-resource.openai.azure.com/"
 *   apiVersion = "2025-01-01-preview"
 * }
 * }}}
 */
object AzureProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("azure")

  /** The key naming the Azure OpenAI deployment endpoint. */
  val EndpointKey: String = "endpoint"

  /** The key naming the Azure OpenAI API version. */
  val ApiVersionKey: String = "apiVersion"

  /** The key falls back to `llm4s.credentials.azure.apiKey`, bound to `AZURE_OPENAI_API_KEY`. */
  val configSpec: ProviderConfigSpec = ProviderConfigSpec(
    requiresApiKey = true,
    apiKeyEnv = Seq(OpenAIConfigKeys.AZURE_OPENAI_API_KEY),
    extras = Seq(
      ProviderConfigKey(
        EndpointKey,
        "the model endpoint/deployment name in your Azure OpenAI resource",
        required = true,
        env = Some(OpenAIConfigKeys.AZURE_API_BASE)
      ),
      ProviderConfigKey(
        ApiVersionKey,
        "the Azure OpenAI API version",
        default = Some(AzureConfig.DEFAULT_API_VERSION),
        env = Some(OpenAIConfigKeys.AZURE_API_VERSION)
      )
    )
  )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      endpoint <- ProviderDescriptor.requireExtra(providerName, section, EndpointKey)
      apiKey   <- ProviderDescriptor.requireApiKey(providerName, section)
      apiVersion = section.extra(ApiVersionKey).getOrElse(AzureConfig.DEFAULT_API_VERSION)
      config <- AzureConfig.fromValues(section.model.asString, endpoint, apiKey, apiVersion)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[AzureConfig](id, config)
      .flatMap(OpenAIClient(_, options.metrics, options.exchangeLogging))
