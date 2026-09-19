package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ CohereConfig, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor, ProviderFeatures }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the Cohere API. */
object CohereProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("cohere")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(CohereConfig.DEFAULT_BASE_URL)

  /** `CohereClient.streamComplete` returns a `Left` - see #925. */
  override val features: ProviderFeatures = ProviderFeatures(streaming = false)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
    yield CohereConfig.fromValues(section.model.asString, apiKey, baseUrl)

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[CohereConfig](id, config)
      .flatMap(CohereClient(_, options.metrics, options.exchangeLogging))
