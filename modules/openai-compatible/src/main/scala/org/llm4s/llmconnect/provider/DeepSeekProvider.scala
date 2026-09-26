package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ DeepSeekModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, DeepSeekConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the DeepSeek API. */
object DeepSeekProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("deepseek")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(DeepSeekConfig.DEFAULT_BASE_URL)

  override val modelLister: Option[ProviderModelLister] = Some(DeepSeekModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- DeepSeekConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[DeepSeekConfig](id, config)
      .flatMap(DeepSeekClient(_, options.metrics, options.exchangeLogging))
