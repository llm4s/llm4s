package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.config.{ DeepSeekConfig, MistralConfig, OpenAICompatibleConfig }
import org.llm4s.llmconnect.provider.{ OpenRouterDialect, OpenRouterProvider }
import org.llm4s.types.Result

/**
 * Model lister for the DeepSeek provider, using its OpenAI-compatible `/models` endpoint.
 *
 * This was `ProviderModelListers.DeepSeek` until the provider moved to
 * `llm4s-openai-compatible` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object DeepSeekModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(ProviderId("deepseek"), DeepSeekConfig.DEFAULT_BASE_URL)

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    delegate.listModels(config, httpClient)

/**
 * Model lister for the OpenRouter provider, sending the `HTTP-Referer` and
 * `X-Title` headers OpenRouter asks for, and the section's `organization`, if set, as
 * `OpenAI-Organization`, as it always has.
 *
 * This was `ProviderModelListers.OpenRouter` until the provider moved to
 * `llm4s-openai-compatible` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object OpenRouterModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(
      ProviderId("openrouter"),
      OpenRouterProvider.DEFAULT_BASE_URL,
      extraHeaders = OpenRouterDialect.headers.toMap,
      sectionHeaders = ProviderModelListers.openAIOrganizationHeader
    )

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    delegate.listModels(config, httpClient)

/**
 * Model lister for the Mistral provider: `GET <api base>/models`, where the API base is the
 * one chat posts `/chat/completions` under - [[org.llm4s.llmconnect.config.MistralConfig.apiBaseUrl]]
 * of the section's `baseUrl` (the API root, `https://api.mistral.ai` by default), so
 * `https://api.mistral.ai` and `https://api.mistral.ai/v1` both list `/v1/models`.
 *
 * This was `ProviderModelListers.Mistral` until the provider moved to
 * `llm4s-openai-compatible` ([[https://github.com/llm4s/llm4s/issues/1132 #1132]]).
 */
@Stable
object MistralModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(
      ProviderId("mistral"),
      MistralConfig.apiBaseUrl(MistralConfig.DEFAULT_BASE_URL)
    )

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    // The same normalisation chat applies, so a base URL that already carries `/v1` does not
    // list `/v1/v1/models`.
    val apiBase = config.baseUrl.map(url => BaseUrl(MistralConfig.apiBaseUrl(url.asUrl)))
    delegate.listModels(config.withBaseUrl(apiBase), httpClient)

/**
 * Model lister for the generic `openai-compatible` provider: `GET <baseUrl>/models`.
 *
 * The section's `baseUrl` is required - there is no default endpoint - and its
 * `apiKey` is optional, as it is for chat: a local server such as vLLM, LM
 * Studio or llama.cpp lists its models without one. The section's `headers`
 * are sent too.
 */
@Stable
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
      delegate.listModels(config.withBaseUrl(Some(BaseUrl(baseUrl.asUrl.stripSuffix("/")))), httpClient)
    }
