package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.OpenAICompatibleConfigKeys
import org.llm4s.config.NamedProviderConfig
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, ProviderConfig, ZaiConfig }
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

/** Registration for the Z.ai GLM API. */
@Stable
object ZaiProvider extends ProviderDescriptor:

  /** Sends its requests through `LlmClientOptions.httpClient` when one is supplied (#2341). */
  override def features: ProviderFeatures = ProviderFeatures.default.withCustomHttpClient(true)

  val id: ProviderId = ProviderId("zai")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(ZaiConfig.DEFAULT_BASE_URL, Seq(OpenAICompatibleConfigKeys.ZAI_API_KEY))

  def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
    given ContextWindowResolver = context.contextWindows
    for
      apiKey  <- ProviderDescriptor.requireApiKey(context.providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(context.providerName, section, configSpec)
      config  <- ZaiConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
    given ModelRegistryService = context.modelRegistry
    ProviderDescriptor
      .expectConfig[ZaiConfig](id, config)
      .flatMap(
        ZaiClient(_, context.metrics, context.exchangeLogging, Llm4sHttpClient.suppliedOrFromConfig(context.httpClient))
      )
