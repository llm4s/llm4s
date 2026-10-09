package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ ApiKey, BaseUrl, NamedProviderConfig, ProviderId }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.llmconnect.auth.TokenExchange
import org.llm4s.llmconnect.config.{ DeepSeekConfig, MistralConfig, OpenAICompatibleConfig }
import org.llm4s.llmconnect.provider.{ OpenAICompatibleProvider, OpenRouterDialect, OpenRouterProvider }
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
 * are sent too. The section is validated exactly as chat validates it - the lister builds its config
 * through the provider's own `buildConfig` path - before any request, so a section with `auth` and a
 * plain-http `baseUrl` or `tokenUrl` is refused without exchanging the identity token.
 */
@Stable
object OpenAICompatibleModelLister extends ProviderModelLister:
  private val delegate =
    ProviderModelListers.openAICompatible(
      ProviderId(OpenAICompatibleConfig.ProviderIdName),
      defaultBaseUrl = "",
      apiKeyRequired = false
    )

  /**
   * The name model-listing errors give the section. A [[ProviderModelLister]] is handed the section, not its
   * name, so validation messages read `llm4s.providers.model-lister.<key>`.
   */
  private val SectionLabel = "model-lister"

  def listModels(config: NamedProviderConfig, httpClient: Llm4sHttpClient): Result[List[DiscoveredModel]] =
    for
      _ <- config.requireProvider(ProviderId(OpenAICompatibleConfig.ProviderIdName))
      // Checked first only for its message, which names no section: the lister is not told the section's name.
      _ <- config.requireBaseUrl
      // The config chat would build, validated the same way and before any request: a section chat refuses
      // (a plain-http baseUrl or tokenUrl with auth, an apiKey or Authorization header beside auth, ...) is
      // refused here too, so no identity token is exchanged and no access token sent for it.
      valid <- OpenAICompatibleProvider.validatedConfig(SectionLabel, config)(_ => None)
      // A section with `auth` is exchanged once, and lists with the access token as its key.
      bearer <- valid.tokenExchange match
        case None           => Right(valid.apiKey)
        case Some(exchange) => TokenExchange.rfc8693(exchange, httpClient)().map(token => Some(token.value))
      models <- delegate.listModels(
        config
          .withAuth(None)
          .withApiKey(bearer.map(ApiKey(_)))
          .withBaseUrl(Some(BaseUrl(valid.baseUrl)))
          .withHeaders(valid.headers),
        httpClient
      )
    yield models
