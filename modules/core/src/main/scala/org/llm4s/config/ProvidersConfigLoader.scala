package org.llm4s.config

import org.llm4s.error.ConfigurationError
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
 */
final private[config] case class ProviderSections(
  selectedProvider: Option[ProviderName],
  sections: Map[ProviderName, Result[RawNamedProviderSection]]
):

  /**
   * The default section's name, if one is set and a section of that name exists.
   *
   * Whether that section is valid is left to [[validated]]: the name alone does not need it.
   */
  def defaultProviderName: Result[ProviderName] =
    selectedProvider match
      case None =>
        Left(ConfigurationError("No default provider configured under llm4s.providers.provider"))
      case Some(name) if !sections.contains(name) =>
        Left(ConfigurationError(s"Configured provider '${name.asName}' was not found"))
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
      .toRight(ConfigurationError(s"Configured provider '${name.asName}' was not found"))
      .flatMap(identity)
      .flatMap(NamedProviderConfigValidator.validate(name, _))

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
    RawProvidersConfigLoader.load(source).flatMap(validate)

  /**
   * Validates a `RawProvidersConfig` by normalizing all named providers and checking the selected provider.
   *
   *  @param raw the raw providers config to validate
   *  @return `Right(ProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(raw: RawProvidersConfig)(using ProviderRegistry): Result[ProvidersConfig] =
    for
      namedProviders <- validateNamedProviders(raw.namedProviders)
      _              <- validateSelectedProvider(raw.selectedProvider, namedProviders)
    yield ProvidersConfig(
      selectedProvider = raw.selectedProvider,
      namedProviders = namedProviders,
    )

  private def validateNamedProviders(
    rawNamedProviders: Map[ProviderName, RawNamedProviderSection]
  )(using ProviderRegistry): Result[Map[ProviderName, NamedProviderConfig]] =
    rawNamedProviders.foldLeft[Result[Map[ProviderName, NamedProviderConfig]]](Right(Map.empty)):
      case (accResult, (providerName, rawSection)) =>
        for
          acc        <- accResult
          normalized <- NamedProviderConfigValidator.validate(providerName, rawSection)
        yield acc.updated(providerName, normalized)

  private def validateSelectedProvider(
    selectedProvider: Option[ProviderName],
    namedProviders: Map[ProviderName, NamedProviderConfig]
  ): Result[Unit] =
    selectedProvider match
      case Some(providerName) if !namedProviders.contains(providerName) =>
        Left(ConfigurationError(s"Configured provider '${providerName.asName}' was not found"))
      case _ =>
        Right(())
