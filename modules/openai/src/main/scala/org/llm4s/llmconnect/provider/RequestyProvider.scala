package org.llm4s.llmconnect.provider

import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.config.{ OpenAIConfigKeys, ProviderModelLister, RequestyModelLister }
import org.llm4s.llmconnect.config.{ ContextWindowResolver, OpenAIConfig, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for Requesty.
 *
 * Requesty is an OpenAI-compatible router: it reuses both `OpenAIConfig` and
 * `OpenAIClient`, and differs only in its default base URL.
 */
object RequestyProvider extends ProviderDescriptor:
  val id: ProviderId = ProviderId("requesty")

  /**
   * The Requesty router base URL used when a provider section sets no `baseUrl`.
   *
   * This was `DefaultConfig.DEFAULT_REQUESTY_BASE_URL` in `llm4s-core` until the
   * provider moved to `llm4s-openai` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
   */
  val DEFAULT_BASE_URL: String = "https://router.requesty.ai/v1"

  /** The key falls back to `llm4s.credentials.requesty.apiKey`, bound to `REQUESTY_API_KEY`. */
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(DEFAULT_BASE_URL, Seq(OpenAIConfigKeys.REQUESTY_API_KEY))
      .copy(extras = Seq(OpenAIConfig.OrganizationConfigKey))

  override val modelLister: Option[ProviderModelLister] = Some(RequestyModelLister)

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config <- OpenAIConfig.fromValues(
        section.model.asString,
        apiKey,
        section.extra(OpenAIConfig.OrganizationKey),
        baseUrl,
        Some(id)
      )
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[OpenAIConfig](id, config)
      // Labelled with this descriptor's id rather than the config's `providerId`, so a config
      // built by hand - whose id is inferred from its base URL as `openai` - is still `requesty`
      // here. One built by `buildConfig` carries `requesty` itself (#1132).
      .flatMap(OpenAIClient.forProvider(_, id, options.metrics, options.exchangeLogging))
