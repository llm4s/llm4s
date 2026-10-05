package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result

/** Shared model types and configuration data structures for the multi-provider configuration system. */
@Stable
object ProvidersConfigModel:
  export org.llm4s.types.ProviderModelTypes.*

  /**
   * Raw, unvalidated provider section as read directly from the configuration source.
   *
   *  @param provider    the provider kind string (e.g. "openai", "anthropic")
   *  @param model       the model name string
   *  @param baseUrl     optional override for the provider's base URL
   *  @param apiKey      provider-dependent credential: an API key for most providers,
   *                     or a path to a service-account credentials file for VertexAI
   *  @param headers     optional extra HTTP headers sent on every request
   *  @param extras      every other key in the section, as a string: the provider-specific keys a
   *                     descriptor declares in `ProviderConfigSpec.extras`, and anything unknown,
   *                     which validation reports and drops
   */
  final private[llm4s] case class RawNamedProviderSection(
    provider: Option[String],
    model: Option[String],
    baseUrl: Option[String],
    apiKey: Option[String],
    headers: Option[Map[String, String]] = None,
    extras: Map[String, String] = Map.empty
  )

  /**
   * Raw top-level providers configuration as read from the configuration source.
   *
   *  @param selectedProvider the name of the default provider, if set
   *  @param namedProviders   map of provider name to its raw section
   */
  final private[llm4s] case class RawProvidersConfig(
    selectedProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, RawNamedProviderSection]
  )

  /**
   * Validated and normalised configuration for a single named provider.
   *
   *  @param provider     the resolved `ProviderId`
   *  @param model        the model name to use
   *  @param baseUrl      optional base URL override
   *  @param apiKey       provider-dependent credential: an API key for most providers,
   *                      or a path to a service-account credentials file for VertexAI
   *  @param headers      extra HTTP headers sent on every request; read by `openai-compatible`
   *                      and the OpenAI-format model listers, and ignored by the rest. Values are
   *                      redacted in `toString`.
   *  @param extras       the provider-specific keys its descriptor declares in
   *                      `ProviderConfigSpec.extras`, as validation leaves them: required ones
   *                      present, defaults filled in, deprecated aliases resolved to the current
   *                      name, and undeclared keys dropped. Values are redacted in `toString`,
   *                      since a provider may declare a credential here. Vendor-specific settings
   *                      live here rather than as fields - Azure's `endpoint` and `apiVersion`,
   *                      OpenAI's `organization`, the generic `openai-compatible` provider's
   *                      `contextWindow` and `reserveCompletion` - so that this type does not
   *                      change as providers come and go.
   */
  final case class NamedProviderConfig private (
    provider: ProviderId,
    model: ModelName,
    baseUrl: Option[BaseUrl],
    apiKey: Option[ApiKey],
    headers: Map[String, String],
    extras: Map[String, String]
  ):

    def withProvider(provider: ProviderId): NamedProviderConfig        = copy(provider = provider)
    def withModel(model: ModelName): NamedProviderConfig               = copy(model = model)
    def withBaseUrl(baseUrl: BaseUrl): NamedProviderConfig             = copy(baseUrl = Some(baseUrl))
    def withBaseUrl(baseUrl: Option[BaseUrl]): NamedProviderConfig     = copy(baseUrl = baseUrl)
    def withApiKey(apiKey: ApiKey): NamedProviderConfig                = copy(apiKey = Some(apiKey))
    def withApiKey(apiKey: Option[ApiKey]): NamedProviderConfig        = copy(apiKey = apiKey)
    def withHeaders(headers: Map[String, String]): NamedProviderConfig = copy(headers = headers)
    def withExtras(extras: Map[String, String]): NamedProviderConfig   = copy(extras = extras)
    // The API key, header values and extra values may be credentials (`x-api-key`, a gateway
    // token, a provider-declared secret), so all are redacted; names are kept because they are
    // what a user needs to debug a section.
    override def toString: String =
      s"NamedProviderConfig($provider,$model,$baseUrl,${apiKey.map(_ => "***")},${redacted(headers)},${redacted(extras)})"

    private def redacted(values: Map[String, String]): String =
      values.keys.map(k => s"$k -> ***").mkString("Map(", ", ", ")")

    /**
     * The value of a provider-specific key, as validation left it.
     *
     *  @param key a key the provider declares in `ProviderConfigSpec.extras`
     */
    def extra(key: String): Option[String] = extras.get(key)

    /**
     * Returns this config if its provider matches `expected`, otherwise a `ConfigurationError`.
     *
     *  @param expected the `ProviderId` that is required
     *  @return `Right(this)` when the provider matches, or `Left` with a descriptive error
     */
    def requireProvider(expected: ProviderId): Result[NamedProviderConfig] =
      if provider == expected then Right(this)
      else
        Left(
          ConfigurationError(
            s"Expected a '${expected.asString}' provider section, but it is configured for '${provider.asString}'"
          )
        )

    /**
     * Returns the configured base URL or fails with a `ConfigurationError` if absent.
     *
     *  @return `Right(BaseUrl)` when present, or `Left` with an error
     */
    def requireBaseUrl: Result[BaseUrl] =
      baseUrl.toRight(ConfigurationError("Configured provider is missing required field `baseUrl`"))

    /**
     * Returns the configured base URL, falling back to `default` when absent.
     *
     *  @param default by-name fallback string used to construct a `BaseUrl`
     *  @return the configured or default `BaseUrl`
     */
    def baseUrlOrDefault(default: => String): BaseUrl =
      baseUrl.getOrElse(BaseUrl(default))

    /**
     * Returns the configured API key or fails with a `ConfigurationError` if absent.
     *
     *  @return `Right(ApiKey)` when present, or `Left` with an error
     */
    def requireApiKey: Result[ApiKey] =
      apiKey.toRight(ConfigurationError("Configured provider is missing required field `apiKey`"))

  object NamedProviderConfig {

    /** Creates a [[NamedProviderConfig]]. Named arguments are the supported way to construct one. */
    def apply(
      provider: ProviderId,
      model: ModelName,
      baseUrl: Option[BaseUrl],
      apiKey: Option[ApiKey],
      headers: Map[String, String] = Map.empty,
      extras: Map[String, String] = Map.empty
    ): NamedProviderConfig =
      new NamedProviderConfig(provider, model, baseUrl, apiKey, headers, extras)
  }

  /**
   * Validated top-level providers configuration, including all named provider entries.
   *
   *  @param selectedProvider the name of the default provider, if configured
   *  @param namedProviders   map of provider name to validated `NamedProviderConfig`
   */
  final case class ProvidersConfig(
    selectedProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, NamedProviderConfig]
  ):
    /**
     * Returns the name of the default provider or a `ConfigurationError` if none is selected.
     *
     *  @return `Right(ProviderName)` when a default is configured, or `Left` with an error
     */
    def defaultProviderName: Result[ProviderName] =
      selectedProvider.toRight(
        ConfigurationError("No default provider configured under llm4s.providers.provider")
      )
