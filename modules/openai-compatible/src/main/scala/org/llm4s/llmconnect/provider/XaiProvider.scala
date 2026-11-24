package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.config.OpenAICompatibleConfigKeys
import org.llm4s.config.NamedProviderConfig
import org.llm4s.config.{ ProviderModelLister, XaiModelLister }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, XaiConfig }
import org.llm4s.llmconnect.spi.{
  ProviderBuildContext,
  ProviderConfigContext,
  ProviderConfigSpec,
  ProviderDescriptor,
  ProviderFeatures
}
import org.llm4s.llmconnect.LLMClient
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for the xAI (Grok) API, through its OpenAI-compatible Chat Completions endpoint
 * ([[https://github.com/llm4s/llm4s/issues/1023 #1023]]). The key defaults to `XAI_API_KEY`, bound
 * to `llm4s.credentials.xai.apiKey`, and the base URL to
 * [[org.llm4s.llmconnect.config.XaiConfig.DEFAULT_BASE_URL]].
 */
@Experimental
object XaiProvider extends ProviderDescriptor:

  /** Sends its requests through `LlmClientOptions.httpClient` when one is supplied (#2341). */
  override def features: ProviderFeatures = ProviderFeatures.default.withCustomHttpClient(true)

  val id: ProviderId = ProviderId("xai")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(XaiConfig.DEFAULT_BASE_URL, Seq(OpenAICompatibleConfigKeys.XAI_API_KEY))

  override val modelLister: Option[ProviderModelLister] = Some(XaiModelLister)

  def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
    given ContextWindowResolver = context.contextWindows
    for
      apiKey  <- ProviderDescriptor.requireApiKey(context.providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(context.providerName, section, configSpec)
      config  <- XaiConfig.fromValues(section.model, apiKey, baseUrl)
    yield config

  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
    given ModelRegistryService = context.modelRegistry
    ProviderDescriptor
      .expectConfig[XaiConfig](id, config)
      .flatMap(
        XaiClient(_, context.metrics, context.exchangeLogging, Llm4sHttpClient.suppliedOrFromConfig(context.httpClient))
      )
