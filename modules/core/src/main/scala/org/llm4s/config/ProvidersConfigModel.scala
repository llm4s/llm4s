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
   */
  final case class RawNamedProviderSection(
    provider: Option[String],
    model: Option[String],
    baseUrl: Option[String],
    apiKey: Option[String],
    organization: Option[String],
    endpoint: Option[String],
    apiVersion: Option[String]
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
   *  @param provider     the resolved `ProviderKind`
   *  @param model        the model name to use
   *  @param baseUrl      optional base URL override
   *  @param apiKey       provider-dependent credential: an API key for most providers,
   *                      or a path to a service-account credentials file for VertexAI
   *  @param organization provider-dependent: the organisation identifier for OpenAI,
   *                      or the GCP region/location for VertexAI
   *  @param endpoint     provider-dependent: the endpoint URL for Azure, or the GCP
   *                      project ID for VertexAI
   *  @param apiVersion   optional API version string (Azure-specific)
   */
  final case class NamedProviderConfig(
    provider: ProviderKind,
    model: ModelName,
    baseUrl: Option[BaseUrl],
    apiKey: Option[ApiKey],
    organization: Option[String],
    endpoint: Option[String],
    apiVersion: Option[String]
  ):
    /**
     * Returns this config if its provider matches `expected`, otherwise a `ConfigurationError`.
     *
     *  @param expected the `ProviderKind` that is required
     *  @return `Right(this)` when the provider matches, or `Left` with a descriptive error
     */
    def requireProvider(expected: ProviderKind): Result[NamedProviderConfig] =
      if provider == expected then Right(this)
      else
        Left(
          ConfigurationError(
            s"Model discovery is not supported yet for provider '${provider.toString.toLowerCase}'"
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
