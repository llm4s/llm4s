package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Experimental
import org.llm4s.config.OpenAICompatibleConfigKeys
import org.llm4s.config.ProvidersConfigModel.NamedProviderConfig
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ CohereConfig, ContextWindowResolver, ProviderConfig }
import org.llm4s.llmconnect.spi.{ ProviderConfigSpec, ProviderDescriptor, ProviderFeatures }
import org.llm4s.llmconnect.{ LLMClient, LlmClientOptions }
import org.llm4s.model.ModelRegistryService
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

/**
 * Registration for the Cohere API, through its OpenAI-compatibility endpoint.
 *
 * Streaming is supported - the default [[org.llm4s.llmconnect.spi.ProviderFeatures]] - since
 * Cohere moved onto the shared OpenAI-compatible client
 * ([[https://github.com/llm4s/llm4s/issues/925 #925]], [[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Experimental
object CohereProvider extends ProviderDescriptor:

  /** Sends its requests through `LlmClientOptions.httpClient` when one is supplied (#2341). */
  override def features: ProviderFeatures = ProviderFeatures.default.withCustomHttpClient(true)

  val id: ProviderId = ProviderId("cohere")

  val configSpec: ProviderConfigSpec =
    ProviderConfigSpec.apiKeyAndDefaultBaseUrl(
      CohereConfig.DEFAULT_BASE_URL,
      Seq(OpenAICompatibleConfigKeys.COHERE_API_KEY)
    )

  def buildConfig(providerName: String, section: NamedProviderConfig)(using
    ContextWindowResolver
  ): Result[ProviderConfig] =
    for
      apiKey  <- ProviderDescriptor.requireApiKey(providerName, section)
      baseUrl <- ProviderDescriptor.resolveBaseUrl(providerName, section, configSpec)
      config  <- CohereConfig.fromValues(section.model.asString, apiKey, baseUrl)
    yield config

  def buildClient(config: ProviderConfig, options: LlmClientOptions)(using
    ModelRegistryService
  ): Result[LLMClient] =
    ProviderDescriptor
      .expectConfig[CohereConfig](id, config)
      .flatMap(
        CohereClient(
          _,
          options.metrics,
          options.exchangeLogging,
          Llm4sHttpClient.suppliedOrFromConfig(options.httpClient)
        )
      )
