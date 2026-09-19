package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, ZaiConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the Z.ai GLM API. */
object ZaiProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("zai")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(ZaiConfig.DEFAULT_BASE_URL)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
    yield ZaiConfig.fromValues(section.model.asString, apiKey, baseUrl)

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[ZaiConfig](id, config)
      .flatMap(ZaiClient(_, options.metrics, options.exchangeLogging))
