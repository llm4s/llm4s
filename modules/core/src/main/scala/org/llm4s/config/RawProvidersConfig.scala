package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.ProviderTimeouts
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result
import org.llm4s.util.Redaction

/** How the configuration types print a map that may hold credentials. */
private[config] object ConfigRedaction:

  /** A map with its values hidden: the names are what a user needs to debug a section. */
  def values(values: Map[String, String]): String =
    values.keys.map(k => s"$k -> ***").mkString("Map(", ", ", ")")

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
 *  @param timeouts    the `timeouts` block as read, not yet checked for positive values
 *  @param auth         the section's `auth` block, if any, as scalars: the identity-token key and
 *                      whatever the provider reads from it
 *  @param http         the section's `http` block, if any, as read: its proxy and TLS keys override
 *                      `llm4s.http`'s for this section
 *  @param apiKeyFile   the section's `apiKeyFile`, as read: a file holding the key, re-read when it changes
 */
final private[llm4s] case class RawNamedProviderSection(
  provider: Option[String],
  model: Option[String],
  baseUrl: Option[String],
  apiKey: Option[ApiKey],
  headers: Option[Map[String, String]] = None,
  extras: Map[String, String] = Map.empty,
  timeouts: Option[ProviderTimeouts] = None,
  auth: Option[Map[String, String]] = None,
  http: Option[com.typesafe.config.ConfigObject] = None,
  apiKeyFile: Option[String] = None
):
  // As NamedProviderConfig.toString: header, extra and auth values may be credentials (a literal
  // `identityToken`, a gateway token), and so may the userinfo of the base URL or a proxy password in
  // the `http` block, so only their names are shown.
  override def toString: String =
    s"RawNamedProviderSection($provider,$model,${baseUrl.map(Redaction.url)},${apiKey.map(_ => "***")}," +
      s"${headers.map(ConfigRedaction.values)},${ConfigRedaction.values(extras)},$timeouts,${auth.map(ConfigRedaction.values)}," +
      s"${http.map(block => ConfigRedaction.values(block.keySet.toArray.map(_.toString -> "").toMap))},$apiKeyFile)"

/**
 * Raw top-level providers configuration as read from the configuration source.
 *
 *  @param defaultProvider the name of the default provider, if set
 *  @param namedProviders   map of provider name to its raw section
 */
final private[llm4s] case class RawProvidersConfig(
  defaultProvider: Option[ProviderName],
  namedProviders: Map[ProviderName, RawNamedProviderSection]
)

/**
 * Validated top-level providers configuration, including all named provider entries. Internal: an
 * application reads sections one at a time (`Llm4sConfig.provider`, `providerConfigs`).
 *
 *  @param defaultProvider the name of the default provider, if configured
 *  @param namedProviders   map of provider name to validated `NamedProviderConfig`
 */
final private[llm4s] case class ProvidersConfig private (
  defaultProvider: Option[ProviderName],
  namedProviders: Map[ProviderName, NamedProviderConfig]
):
  /**
   * Returns the name of the default provider or a `ConfigurationError` if none is selected.
   *
   *  @return `Right(ProviderName)` when a default is configured, or `Left` with an error
   */
  def defaultProviderName: Result[ProviderName] =
    defaultProvider.toRight(
      ConfigurationError(ProviderSections.noDefaultMessage)
    )

  /** Sets the default provider's name. */
  def withDefaultProvider(defaultProvider: ProviderName): ProvidersConfig =
    copy(defaultProvider = Some(defaultProvider))

  /** Sets the default provider's name, or clears it with `None`. */
  def withDefaultProvider(defaultProvider: Option[ProviderName]): ProvidersConfig =
    copy(defaultProvider = defaultProvider)

  /** Sets the named provider sections. */
  def withNamedProviders(namedProviders: Map[ProviderName, NamedProviderConfig]): ProvidersConfig =
    copy(namedProviders = namedProviders)

private[llm4s] object ProvidersConfig:

  /** Creates a [[ProvidersConfig]]. */
  def apply(
    defaultProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, NamedProviderConfig]
  ): ProvidersConfig =
    new ProvidersConfig(defaultProvider, namedProviders)
