package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ ProviderModelLister, ProviderModelListers }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, MistralConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor, ProviderFeatures }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the Mistral API. */
object MistralProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("mistral")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(MistralConfig.DEFAULT_BASE_URL)

  /** `MistralClient.streamComplete` returns a `Left` - see #925. */
  override val features: ProviderFeatures = ProviderFeatures(streaming = false)

  override val modelLister: Option[ProviderModelLister] = Some(ProviderModelListers.Mistral)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
    yield MistralConfig.fromValues(section.model.asString, apiKey, baseUrl)

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[MistralConfig](id, config)
      .flatMap(MistralClient(_, options.metrics, options.exchangeLogging))
