package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.config.NamedProviderConfig
import org.llm4s.config.{ GeminiConfigKeys, GeminiModelLister, ProviderModelLister }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ ContextWindowResolver, GeminiConfig, ProviderConfig }
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

/** Registration for the Google Gemini API. */
@Stable
object GeminiProvider extends ProviderDescriptor:

  /** Sends its requests through `LlmClientOptions.httpClient` when one is supplied (#2341). */
  override def features: ProviderFeatures = ProviderFeatures.default.withCustomHttpClient(true)

  val id: ProviderId = ProviderId("gemini")

  /** `provider = "google"` has always been accepted for Gemini. */
  override val aliases: Set[String] = Set("google")

  /**
   * The key falls back to `llm4s.credentials.gemini.apiKey` - for `provider = "google"` too,
   * which is this provider under an alias - bound to `GOOGLE_API_KEY`, else `GEMINI_API_KEY`.
   */
  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec
      .apiKeyAndDefaultBaseUrl(
        GeminiConfig.DEFAULT_BASE_URL,
        Seq(GeminiConfigKeys.GOOGLE_API_KEY, GeminiConfigKeys.GEMINI_API_KEY)
      )
      .withExtras(Seq(GeminiGrounding.GoogleSearchConfigKey))

  override val modelLister: Option[ProviderModelLister] = Some(GeminiModelLister)

  def buildConfig(section: NamedProviderConfig, context: ProviderConfigContext): Result[ProviderConfig] =
    given ContextWindowResolver = context.contextWindows
    for
      apiKey  <- ProviderDescriptor.requireApiKey(context.providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(context.providerName, section, configSpec)
      search  <- GeminiGrounding.googleSearch(context.providerName, section)
      config  <- GeminiConfig.fromValues(section.model, apiKey, baseUrl)
    yield config.withGoogleSearch(search)

  def build(config: ProviderConfig, context: ProviderBuildContext): Result[LLMClient] =
    given ModelRegistryService = context.modelRegistry
    ProviderDescriptor
      .expectConfig[GeminiConfig](id, config)
      .flatMap(
        GeminiClient(
          _,
          context.metrics,
          context.exchangeLogging,
          Llm4sHttpClient.suppliedOrFromConfig(context.httpClient)
        )
      )
