package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.config.OpenAICompatibleConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ ProviderModelLister, XaiModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, XaiConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for the xAI (Grok) API, through its OpenAI-compatible Chat Completions endpoint
 * ([[https://github.com/llm4s/llm4s/issues/1023 #1023]]). The key defaults to `XAI_API_KEY`, bound
 * to `llm4s.credentials.xai.apiKey`, and the base URL to [[XaiConfig.DEFAULT_BASE_URL]].
 */
@Experimental
object XaiProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("xai")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(XaiConfig.DEFAULT_BASE_URL, Seq(OpenAICompatibleConfigKeys.XAI_API_KEY))

  override val modelLister: Option[ProviderModelLister] = Some(XaiModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- XaiConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[XaiConfig](id, config)
      .flatMap(XaiClient(_, options.metrics, options.exchangeLogging))
