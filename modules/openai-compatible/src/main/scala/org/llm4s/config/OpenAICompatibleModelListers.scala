package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ DeepSeekConfig, OpenAICompatibleConfig }
import org.llm4s.types.Result

/**
 * Model lister for the DeepSeek provider, using its OpenAI-compatible `/models` endpoint.
 *
 * This was `ProviderModelListers.DeepSeek` until the provider moved to
 * `llm4s-openai-compatible` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
object DeepSeekModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(ProviderId("deepseek"), DeepSeekConfig.DEFAULT_BASE_URL)

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    delegate.listModels(config, httpClient)

/**
 * Model lister for the generic `openai-compatible` provider: `GET <baseUrl>/models`.
 *
 * The section's `baseUrl` is required - there is no default endpoint - and its
 * `apiKey` is optional, as it is for chat: a local server such as vLLM, LM
 * Studio or llama.cpp lists its models without one. The section's `headers`
 * are sent too.
 */
object OpenAICompatibleModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(
      ProviderId(OpenAICompatibleConfig.ProviderIdName),
      defaultBaseUrl = "",
      apiKeyRequired = false
    )

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    config.requireBaseUrl.flatMap { baseUrl =>
      // Strip a trailing slash as `OpenAICompatibleConfig.fromValues` does for chat.
      delegate.listModels(config.copy(baseUrl = Some(BaseUrl(baseUrl.asUrl.stripSuffix("/")))), httpClient)
    }
