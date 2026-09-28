package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ AnthropicConfigKeys, AnthropicModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ AnthropicConfig, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the Anthropic Claude API. */
object AnthropicProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("anthropic")

  /** The key falls back to `llm4s.credentials.anthropic.apiKey`, bound to `ANTHROPIC_API_KEY`. */
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(
      AnthropicConfig.DEFAULT_BASE_URL,
      Seq(AnthropicConfigKeys.ANTHROPIC_API_KEY)
    )

  override val modelLister: Option[ProviderModelLister] = Some(AnthropicModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- AnthropicConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[AnthropicConfig](id, config)
      .flatMap(AnthropicClient(_, options.metrics, options.exchangeLogging))
