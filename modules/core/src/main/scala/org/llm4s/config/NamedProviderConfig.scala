package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.AuthConfig
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result
import org.llm4s.util.Redaction

import java.nio.file.Path

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
 *                      present, defaults filled in and undeclared keys dropped. Values are redacted in `toString`,
 *                      since a provider may declare a credential here. Vendor-specific settings
 *                      live here rather than as fields - Azure's `endpoint` and `apiVersion`,
 *                      OpenAI's `organization`, the generic `openai-compatible` provider's
 *                      `contextWindow` and `reserveCompletion` - so that this type does not
 *                      change as providers come and go.
 *  @param timeouts     the section's `timeouts` block: how long a request and a stream may take.
 *                      Absent values leave each client on its own default
 *                      ([[org.llm4s.llmconnect.config.ProviderTimeouts]]). Every provider that makes
 *                      HTTP calls reads it, so it is a built-in field like `headers`, not an extra.
 *  @param auth         the section's workload-identity block, if any: the identity token to
 *                      exchange and the keys its provider declares in
 *                      `ProviderConfigSpec.authExtras`. Validation guarantees it is never set
 *                      together with `apiKey`. Redacted in `toString`.
 *  @param apiKeyFile   the section's `apiKeyFile`: a file holding the key, which the client re-reads when it
 *                      changes, so a rotated key is used without a restart (#2313). Validation reads it once
 *                      into `apiKey`, so a descriptor builds its config as for a literal key; the loader then
 *                      gives the config an `ApiKeyProvider.file` over it.
 */
@Stable
final case class NamedProviderConfig private (
  provider: ProviderId,
  model: ModelName,
  baseUrl: Option[BaseUrl],
  apiKey: Option[ApiKey],
  headers: Map[String, String],
  extras: Map[String, String],
  timeouts: ProviderTimeouts,
  auth: Option[AuthConfig],
  apiKeyFile: Option[Path]
):

  def withProvider(provider: ProviderId): NamedProviderConfig        = copy(provider = provider)
  def withModel(model: ModelName): NamedProviderConfig               = copy(model = model)
  def withBaseUrl(baseUrl: BaseUrl): NamedProviderConfig             = copy(baseUrl = Some(baseUrl))
  def withBaseUrl(baseUrl: Option[BaseUrl]): NamedProviderConfig     = copy(baseUrl = baseUrl)
  def withApiKey(apiKey: ApiKey): NamedProviderConfig                = copy(apiKey = Some(apiKey))
  def withApiKey(apiKey: Option[ApiKey]): NamedProviderConfig        = copy(apiKey = apiKey)
  def withHeaders(headers: Map[String, String]): NamedProviderConfig = copy(headers = headers)
  def withExtras(extras: Map[String, String]): NamedProviderConfig   = copy(extras = extras)
  def withTimeouts(timeouts: ProviderTimeouts): NamedProviderConfig  = copy(timeouts = timeouts)
  def withAuth(auth: AuthConfig): NamedProviderConfig                = copy(auth = Some(auth))
  def withAuth(auth: Option[AuthConfig]): NamedProviderConfig        = copy(auth = auth)
  def withApiKeyFile(apiKeyFile: Path): NamedProviderConfig          = copy(apiKeyFile = Some(apiKeyFile))
  def withApiKeyFile(apiKeyFile: Option[Path]): NamedProviderConfig  = copy(apiKeyFile = apiKeyFile)
  // The API key, header values and extra values may be credentials (`x-api-key`, a gateway
  // token, a provider-declared secret), so all are redacted; names are kept because they are
  // what a user needs to debug a section.
  // The base URL is shown without its userinfo, query or fragment, any of which may hold a credential.
  override def toString: String =
    s"NamedProviderConfig($provider,$model,${baseUrl.map(url => Redaction.url(url.asUrl))}," +
      s"${apiKey.map(_ => "***")},${ConfigRedaction.values(headers)},${ConfigRedaction.values(extras)},$auth" +
      s"${apiKeyFile.fold("")(p => s",apiKeyFile=$p")})"

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
  def baseUrlOrDefault(default: => BaseUrl): BaseUrl =
    baseUrl.getOrElse(default)

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
    extras: Map[String, String] = Map.empty,
    timeouts: ProviderTimeouts = ProviderTimeouts.default,
    auth: Option[AuthConfig] = None,
    apiKeyFile: Option[Path] = None
  ): NamedProviderConfig =
    new NamedProviderConfig(provider, model, baseUrl, apiKey, headers, extras, timeouts, auth, apiKeyFile)

}
