package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OpenAIModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the OpenAI API. */
object OpenAIProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("openai")

  /**
   * The OpenAI API base URL used when a provider section sets no `baseUrl`.
   *
   * This was `DefaultConfig.DEFAULT_OPENAI_BASE_URL` in `llm4s-core` until the
   * provider moved to `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_BASE_URL: String = "https://api.openai.com/v1"

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(DEFAULT_BASE_URL)

  override val modelLister: Option[ProviderModelLister] = Some(OpenAIModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- OpenAIConfig.fromValues(section.model.asString, apiKey, section.organization, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OpenAIConfig](id, config)
      .flatMap(OpenAIClient(_, options.metrics, options.exchangeLogging))
