package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*

/** Validates a raw named provider section for a specific provider type. */
private[llm4s] trait NamedProviderValidator:
  /**
   * Validates the raw provider section and returns a normalised `NamedProviderConfig`.
   *
   *  @param providerName the logical name of the provider entry, used in error messages
   *  @param section      the raw unvalidated provider section
   *  @return `Right(NamedProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(
    providerName: ProviderName,
    section: RawNamedProviderSection
  ): Result[NamedProviderConfig]

/** Per-provider validator implementations for each supported `ProviderKind`. */
private[llm4s] object NamedProviderValidators:

  /** Validator for OpenAI provider configurations. */
  object OpenAI extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.OpenAI,
        section = section,
        requireApiKey = true,
      )

  /** Validator for OpenRouter provider configurations. */
  object OpenRouter extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.OpenRouter,
        section = section,
        requireApiKey = true,
      )

  /** Validator for Azure provider configurations. */
  object Azure extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Azure,
        section = section,
        requireApiKey = true,
        requireEndpoint = true,
      )

  /** Validator for Anthropic provider configurations. */
  object Anthropic extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Anthropic,
        section = section,
        requireApiKey = true,
      )

  /** Validator for Ollama provider configurations. */
  object Ollama extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Ollama,
        section = section,
        requireBaseUrl = true,
      )

  /** Validator for Zai provider configurations. */
  object Zai extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Zai,
        section = section,
        requireApiKey = true,
      )

  /** Validator for Gemini provider configurations. */
  object Gemini extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Gemini,
        section = section,
        requireApiKey = true,
      )

  /** Validator for DeepSeek provider configurations. */
  object DeepSeek extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.DeepSeek,
        section = section,
        requireApiKey = true,
      )

  /** Validator for Cohere provider configurations. */
  object Cohere extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Cohere,
        section = section,
        requireApiKey = true,
      )

  /** Validator for Mistral provider configurations. */
  object Mistral extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Mistral,
        section = section,
        requireApiKey = true,
      )

  private def validateNamedProviderConfig(
    providerName: ProviderName,
    providerKind: ProviderKind,
    section: RawNamedProviderSection,
    requireApiKey: Boolean = false,
    requireBaseUrl: Boolean = false,
    requireEndpoint: Boolean = false
  ): Result[NamedProviderConfig] =
    for
      normalized <- NamedProviderConfigNormalizer.normalize(providerName, section)
      _          <- validateProviderKind(providerName, normalized, providerKind)
      _          <- validateRequiredApiKey(providerName, section, requireApiKey)
      _          <- validateRequiredBaseUrl(providerName, section, requireBaseUrl)
      _          <- validateRequiredEndpoint(providerName, section, requireEndpoint)
    yield normalized

  private def validateProviderKind(
    providerName: ProviderName,
    normalized: NamedProviderConfig,
    expectedKind: ProviderKind
  ): Result[Unit] =
    if normalized.provider == expectedKind then Right(())
    else
      Left(
        ConfigurationError(
          s"Configured provider '${providerName.asName}' resolved to unexpected provider '${normalized.provider.toString}'"
        )
      )

  private def validateRequiredApiKey(
    providerName: ProviderName,
    section: RawNamedProviderSection,
    required: Boolean
  ): Result[Unit] =
    if required then
      section.apiKey
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_ => ())
        .toRight(ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `apiKey`"))
    else Right(())

  private def validateRequiredBaseUrl(
    providerName: ProviderName,
    section: RawNamedProviderSection,
    required: Boolean
  ): Result[Unit] =
    if required then
      section.baseUrl
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_ => ())
        .toRight(
          ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `baseUrl`")
        )
    else Right(())

  private def validateRequiredEndpoint(
    providerName: ProviderName,
    section: RawNamedProviderSection,
    required: Boolean
  ): Result[Unit] =
    if required then
      section.endpoint
        .map(_.trim)
        .filter(_.nonEmpty)
        .map(_ => ())
        .toRight(
          ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `endpoint`")
        )
    else Right(())

/** Dispatches validation of a raw named provider section to the appropriate provider-specific validator. */
private[llm4s] object NamedProviderConfigValidator:

  /**
   * Validates a raw provider section by normalizing it and delegating to the registry-resolved validator.
   *
   *  @param providerName the logical name of the provider entry, used in error messages
   *  @param section      the raw unvalidated provider section
   *  @return `Right(NamedProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(
    providerName: ProviderName,
    section: RawNamedProviderSection
  ): Result[NamedProviderConfig] =
    NamedProviderConfigNormalizer.normalize(providerName, section).flatMap { normalized =>
      ProviderCapabilitiesRegistry
        .forKind(normalized.provider)
        .flatMap(_.validator.validate(providerName, section))
    }
