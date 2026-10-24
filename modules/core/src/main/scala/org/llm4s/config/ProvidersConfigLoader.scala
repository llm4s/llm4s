package org.llm4s.config

import org.llm4s.error.{ ConfigurationError, TroubleshootingLinks }
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*
import pureconfig.ConfigSource

/**
 * The `llm4s.providers` block with each named section read but not yet validated.
 *
 * Resolving one provider validates only that provider's section (#1132). Validating every
 * section on every load meant one section's problem - a `${?VAR}` API key left unset, a
 * provider whose module is not on the classpath - failed every lookup, so a config holding
 * `openai-main` and `anthropic-main` could not load either with only `OPENAI_API_KEY` set.
 *
 *  @param selectedProvider the default section's name (`llm4s.providers.provider`), if set
 *  @param sections         each section as read, or the error reading it
 *  @param credentials      the `llm4s.credentials` block, which a section without an `apiKey`
 *                          of its own falls back to
 *  @param http             the `llm4s.http` block, which a section's own `http` block overrides
 */
final private[config] case class ProviderSections(
  selectedProvider: Option[ProviderName],
  sections: Map[ProviderName, Result[RawNamedProviderSection]],
  credentials: SharedCredentials = SharedCredentials.empty,
  http: Option[com.typesafe.config.ConfigObject] = None
):

  /**
   * The proxy and TLS settings section `name` uses: its own `http` block's keys over `llm4s.http`'s.
   * A section that cannot be read uses `llm4s.http` alone; its read error is reported by [[validated]].
   */
  def network(name: ProviderName): Result[HttpNetworkSettings] =
    HttpNetworkReader.forSection(
      sections.get(name).flatMap(_.toOption).flatMap(_.http),
      http,
      name.asName
    )

  /**
   * The default section's name, if one is set and a section of that name exists.
   *
   * Whether that section is valid is left to [[validated]]: the name alone does not need it.
   */
  def defaultProviderName: Result[ProviderName] =
    selectedProvider match
      case None =>
        Left(ConfigurationError(ProviderSections.noDefaultMessage))
      case Some(name) if !sections.contains(name) =>
        Left(ConfigurationError(ProviderSections.notFoundMessage(name)))
      case Some(name) => Right(name)

  /**
   * Reads and validates the one section `name`, leaving every other section unexamined.
   *
   *  @param name the section to validate
   *  @return the validated section, or the error that names it
   */
  def validated(name: ProviderName)(using ProviderRegistry): Result[NamedProviderConfig] =
    sections
      .get(name)
      .toRight(ConfigurationError(ProviderSections.notFoundMessage(name)))
      .flatMap(identity)
      .flatMap(NamedProviderConfigValidator.validate(name, _, credentials))

  /**
   * Where each section's API key comes from, for sections whose provider requires one.
   *
   * Sections that cannot be read, name no registered provider, or belong to a provider that
   * takes no required key (Ollama, the generic `openai-compatible`) are left out: they cannot
   * inherit a shared key, and their own problems are reported when they are loaded.
   */
  def apiKeySources(using registry: ProviderRegistry): Map[ProviderName, ApiKeySource] =
    sections.toList.flatMap { case (name, section) =>
      for
        raw        <- section.toOption
        provider   <- raw.provider.map(_.trim).filter(_.nonEmpty)
        descriptor <- registry.find(registry.canonicalId(provider))
        if descriptor.configSpec.requiresApiKey
      yield
        val source =
          // Only a provider that accepts `auth` uses it; for any other the section fails to load, and
          // reporting it as authenticated would hide that from the policy check.
          if raw.auth.isDefined && descriptor.configSpec.supportsAuth then
            ApiKeySource.WorkloadIdentity(s"llm4s.providers.${name.asName}.auth")
          else if raw.apiKey.exists(_.trim.nonEmpty) then ApiKeySource.Section(s"llm4s.providers.${name.asName}.apiKey")
          else ApiKeySource.Credentials(SharedCredentials.apiKeyPath(descriptor.id))
        name -> source
    }.toMap

/** The messages of the default-provider errors, each linking its troubleshooting entry (#2285). */
private[config] object ProviderSections:

  /** No `llm4s.providers.provider` is set. */
  val noDefaultMessage: String =
    TroubleshootingLinks.configuration(
      "No default provider configured under llm4s.providers.provider",
      TroubleshootingLinks.Entry.NoDefaultProvider
    )

  /** `name` names no section under `llm4s.providers`. */
  def notFoundMessage(name: ProviderName): String =
    TroubleshootingLinks.configuration(
      s"Configured provider '${name.asName}' was not found",
      TroubleshootingLinks.Entry.ProviderNotFound
    )

/** Loads and validates the full providers configuration from a PureConfig source. */
private[config] object ProvidersConfigLoader:

  /**
   * Reads the providers block without validating any section; see [[ProviderSections]].
   *
   *  @param source the PureConfig source to read from
   *  @return the block, or `Left` when the block itself - not one of its sections - is unreadable
   */
  def loadSections(source: ConfigSource): Result[ProviderSections] =
    RawProvidersConfigLoader.loadSections(source)

  /**
   * Loads raw providers config from `source` and validates it into a `ProvidersConfig`.
   *
   * Every section is validated, so this fails if any one of them is invalid. Resolving a single
   * provider uses [[loadSections]] instead, and validates only that provider's section.
   *
   *  @param source the PureConfig source to read from
   *  @return `Right(ProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def load(source: ConfigSource)(using ProviderRegistry): Result[ProvidersConfig] =
    RawProvidersConfigLoader.load(source).flatMap(validate(_, SharedCredentials.read(source)))

  /**
   * Validates a `RawProvidersConfig` by normalizing all named providers and checking the selected provider.
   *
   *  @param raw         the raw providers config to validate
   *  @param credentials the `llm4s.credentials` block sections without a key fall back to
   *  @return `Right(ProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(
    raw: RawProvidersConfig,
    credentials: SharedCredentials = SharedCredentials.empty
  )(using ProviderRegistry): Result[ProvidersConfig] =
    for
      namedProviders <- validateNamedProviders(raw.namedProviders, credentials)
      _              <- validateSelectedProvider(raw.selectedProvider, namedProviders)
    yield ProvidersConfig(
      selectedProvider = raw.selectedProvider,
      namedProviders = namedProviders,
    )

  private def validateNamedProviders(
    rawNamedProviders: Map[ProviderName, RawNamedProviderSection],
    credentials: SharedCredentials
  )(using ProviderRegistry): Result[Map[ProviderName, NamedProviderConfig]] =
    rawNamedProviders.foldLeft[Result[Map[ProviderName, NamedProviderConfig]]](Right(Map.empty)):
      case (accResult, (providerName, rawSection)) =>
        for
          acc        <- accResult
          normalized <- NamedProviderConfigValidator.validate(providerName, rawSection, credentials)
        yield acc.updated(providerName, normalized)

  private def validateSelectedProvider(
    selectedProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, NamedProviderConfig]
  ): Result[Unit] =
    selectedProvider match
      case Some(providerName) if !namedProviders.contains(providerName) =>
        Left(ConfigurationError(ProviderSections.notFoundMessage(providerName)))
      case _ =>
        Right(())
