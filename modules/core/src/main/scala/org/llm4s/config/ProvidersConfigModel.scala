package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result

/** Shared model types and configuration data structures for the multi-provider configuration system. */
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
   *  @param organization provider-dependent: the organisation identifier for OpenAI,
   *                     or the GCP region/location for VertexAI
   *  @param endpoint    provider-dependent: the endpoint URL for Azure, or the GCP
   *                     project ID for VertexAI
   *  @param apiVersion  optional API version string (Azure-specific)
   *  @param contextWindow     optional context window, for providers that cannot know it (`openai-compatible`)
   *  @param reserveCompletion optional completion reserve, for providers that cannot know it (`openai-compatible`)
   *  @param headers     optional extra HTTP headers sent on every request (`openai-compatible`)
   */
  final case class RawNamedProviderSection(
    provider: Option[String],
    model: Option[String],
    baseUrl: Option[String],
    apiKey: Option[String],
    organization: Option[String],
    endpoint: Option[String],
    apiVersion: Option[String],
    contextWindow: Option[Int] = None,
    reserveCompletion: Option[Int] = None,
    headers: Option[Map[String, String]] = None
  )

  /**
   * Raw top-level providers configuration as read from the configuration source.
   *
   *  @param selectedProvider the name of the default provider, if set
   *  @param namedProviders   map of provider name to its raw section
   */
  final case class RawProvidersConfig(
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
   *  @param organization provider-dependent: the organisation identifier for OpenAI,
   *                      or the GCP region/location for VertexAI
   *  @param endpoint     provider-dependent: the endpoint URL for Azure, or the GCP
   *                      project ID for VertexAI
   *  @param apiVersion   optional API version string (Azure-specific)
   *  @param contextWindow     optional context window. Read by providers that cannot derive one
   *                           from the model name - the generic `openai-compatible` provider -
   *                           and ignored by the rest.
   *  @param reserveCompletion optional completion reserve; read and ignored as `contextWindow` is.
   *  @param headers      extra HTTP headers sent on every request; read by `openai-compatible`
   *                      and ignored by the rest. Values are redacted in `toString`.
   */
  final case class NamedProviderConfig(
    provider: ProviderId,
    model: ModelName,
    baseUrl: Option[BaseUrl],
    apiKey: Option[ApiKey],
    organization: Option[String],
    endpoint: Option[String],
    apiVersion: Option[String],
    contextWindow: Option[Int] = None,
    reserveCompletion: Option[Int] = None,
    headers: Map[String, String] = Map.empty
  ):
    // The API key and header values are credentials (`x-api-key`, a gateway token), so both are
    // redacted; header names are kept because they are what a user needs to debug a section.
    override def toString: String =
      s"NamedProviderConfig($provider,$model,$baseUrl,${apiKey.map(_ => "***")},$organization,$endpoint,$apiVersion," +
        s"$contextWindow,$reserveCompletion,${headers.keys.map(k => s"$k -> ***").mkString("Map(", ", ", ")")})"

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
            s"Model discovery is not supported yet for provider '${provider.asString}'"
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
