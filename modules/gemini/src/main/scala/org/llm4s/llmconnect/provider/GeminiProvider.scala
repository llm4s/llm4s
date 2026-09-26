package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ GeminiModelLister, ProviderModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, GeminiConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/** Registration for the Google Gemini API. */
object GeminiProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("gemini")

  /** `provider = "google"` has always been accepted for Gemini. */
  override val aliases: Set[String] = Set("google")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(GeminiConfig.DEFAULT_BASE_URL)

  override val modelLister: Option[ProviderModelLister] = Some(GeminiModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- GeminiConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[GeminiConfig](id, config)
      .flatMap(GeminiClient(_, options.metrics, options.exchangeLogging))
