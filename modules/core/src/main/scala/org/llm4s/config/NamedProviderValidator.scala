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

  object Requesty extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerKind = ProviderKind.Requesty,
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

  private[config] def validateNamedProviderConfig(
    providerName: ProviderName,
    providerKind: ProviderKind,
    section: RawNamedProviderSection,
    requireApiKey: Boolean = false,
    requireBaseUrl: Boolean = false,
    requireEndpoint: Boolean = false
  ): Result[NamedProviderConfig] =
    NamedProviderConfigNormalizer.normalize(providerName, section).flatMap { normalized =>
      if normalized.provider != providerKind then
        Left(
          ConfigurationError(
            s"Configured provider '${providerName.asName}' resolved to unexpected provider '${normalized.provider.toString}'"
          )
        )
      else
        val missingFields = Seq.newBuilder[String]

        val envPrefix           = providerKind.toString.toUpperCase
        val providerDisplayName = if (providerKind == ProviderKind.Azure) "Azure OpenAI" else providerKind.toString

        if requireApiKey && section.apiKey.map(_.trim).forall(_.isEmpty) then
          missingFields += s"  - apiKey: set ${envPrefix}_API_KEY or provide it in llm4s.conf under providers.${providerName.asName}.apiKey"

        if requireBaseUrl && section.baseUrl.map(_.trim).forall(_.isEmpty) then
          val exampleUrl = providerKind match
            case ProviderKind.Ollama => "e.g. http://localhost:11434"
            case _                   => "e.g. https://api.example.com/"

          missingFields += s"  - baseUrl: set ${envPrefix}_BASE_URL ($exampleUrl)"

        if requireEndpoint && section.endpoint.map(_.trim).forall(_.isEmpty) then
          val exampleMsg = "the model endpoint/deployment name in your Azure OpenAI resource"
          missingFields += s"  - endpoint: $exampleMsg"

        val errors = missingFields.result()
        if errors.nonEmpty then
          Left(
            ConfigurationError(
              s"$providerDisplayName provider '${providerName.asName}' is missing required fields:\n" + errors
                .mkString("\n")
            )
          )
        else Right(normalized)
    }

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
