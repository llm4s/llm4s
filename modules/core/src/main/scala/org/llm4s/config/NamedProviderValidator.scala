package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*

private[llm4s] trait NamedProviderValidator:
  def validate(
    providerName: ProviderName,
    section: RawNamedProviderSection
  ): Result[NamedProviderConfig]

private[llm4s] object NamedProviderValidators:

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

private[llm4s] object NamedProviderConfigValidator:

  def validate(
    providerName: ProviderName,
    section: RawNamedProviderSection
  ): Result[NamedProviderConfig] =
    NamedProviderConfigNormalizer.normalize(providerName, section).flatMap { normalized =>
      ProviderCapabilitiesRegistry
        .forKind(normalized.provider)
        .flatMap(_.validator.validate(providerName, section))
    }
