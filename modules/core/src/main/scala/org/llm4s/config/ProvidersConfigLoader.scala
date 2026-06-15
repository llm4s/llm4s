package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*
import pureconfig.ConfigSource

/** Loads and validates the full providers configuration from a PureConfig source. */
private[config] object ProvidersConfigLoader:

  /**
   * Loads raw providers config from `source` and validates it into a `ProvidersConfig`.
   *
   *  @param source the PureConfig source to read from
   *  @return `Right(ProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def load(source: ConfigSource): Result[ProvidersConfig] =
    RawProvidersConfigLoader.load(source).flatMap(validate)

  /**
   * Validates a `RawProvidersConfig` by normalizing all named providers and checking the selected provider.
   *
   *  @param raw the raw providers config to validate
   *  @return `Right(ProvidersConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(raw: RawProvidersConfig): Result[ProvidersConfig] =
    for
      namedProviders <- validateNamedProviders(raw.namedProviders)
      _              <- validateSelectedProvider(raw.selectedProvider, namedProviders)
    yield ProvidersConfig(
      selectedProvider = raw.selectedProvider,
      namedProviders = namedProviders,
    )

  private def validateNamedProviders(
    rawNamedProviders: Map[ProviderName, RawNamedProviderSection]
  ): Result[Map[ProviderName, NamedProviderConfig]] =
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
