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

/** Per-provider validator implementations for each provider built into `llm4s-core`. */
private[llm4s] object NamedProviderValidators:

  /** Validator for OpenAI provider configurations. */
  object OpenAI extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerId = ProviderId("openai"),
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
        providerId = ProviderId("openrouter"),
        section = section,
        requireApiKey = true,
      )

  /** Validator for Requesty provider configurations. */
  object Requesty extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerId = ProviderId("requesty"),
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
        providerId = ProviderId("azure"),
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
        providerId = ProviderId("anthropic"),
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
        providerId = ProviderId("ollama"),
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
        providerId = ProviderId("zai"),
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
        providerId = ProviderId("gemini"),
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
        providerId = ProviderId("deepseek"),
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
        providerId = ProviderId("cohere"),
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
        providerId = ProviderId("mistral"),
        section = section,
        requireApiKey = true,
      )

  /** Validator for Vertex AI provider configurations. */
  object VertexAI extends NamedProviderValidator:
    def validate(
      providerName: ProviderName,
      section: RawNamedProviderSection
    ): Result[NamedProviderConfig] =
      validateNamedProviderConfig(
        providerName = providerName,
        providerId = ProviderId("vertexai"),
        section = section,
        requireEndpoint = true,
      )

  private[config] def validateNamedProviderConfig(
    providerName: ProviderName,
    providerId: ProviderId,
    section: RawNamedProviderSection,
    requireApiKey: Boolean = false,
    requireBaseUrl: Boolean = false,
    requireEndpoint: Boolean = false
  ): Result[NamedProviderConfig] =
    NamedProviderConfigNormalizer.normalize(providerName, section).flatMap { normalized =>
      if normalized.provider != providerId then
        Left(
          ConfigurationError(
            s"Configured provider '${providerName.asName}' resolved to unexpected provider '${normalized.provider.asString}'"
          )
        )
      else
        val missingFields = Seq.newBuilder[String]

        // `ProviderId` is already the canonical lowercase spelling, so the env-var prefix is a
        // straight upper-casing of it - "openai" -> "OPENAI", as it was under `ProviderKind`.
        val id        = providerId.asString
        val envPrefix = id.toUpperCase

        if requireApiKey && section.apiKey.map(_.trim).forall(_.isEmpty) then
          // Named providers resolve from HOCON, not an automatic <PROVIDER>_API_KEY binding, so lead with the
          // conf path (the real fix) and show how to bind an env var explicitly via a HOCON substitution.
          missingFields += s"  - apiKey: set it in llm4s.conf under providers.${providerName.asName}.apiKey (optionally from an env var, e.g. apiKey = $${?${envPrefix}_API_KEY})"

        if requireBaseUrl && section.baseUrl.map(_.trim).forall(_.isEmpty) then
          // Per-provider example text; folded into `ProviderConfigSpec` when the SPI lands.
          val exampleUrl =
            if id == "ollama" then "e.g. http://localhost:11434"
            else "e.g. https://api.example.com/"

          missingFields += s"  - baseUrl: set ${envPrefix}_BASE_URL ($exampleUrl)"

        if requireEndpoint && section.endpoint.map(_.trim).forall(_.isEmpty) then
          val exampleMsg =
            if id == "vertexai" then "the GCP project ID that owns your Vertex AI resources"
            else "the model endpoint/deployment name in your Azure OpenAI resource"
          missingFields += s"  - endpoint: $exampleMsg"

        val errors = missingFields.result()
        if errors.nonEmpty then
          Left(
            ConfigurationError(
              s"Provider '${providerName.asName}' (provider = $id) is missing required fields:\n" + errors
                .mkString("\n")
            )
          )
        else Right(normalized)
    }

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
        .forProvider(normalized.provider)
        .flatMap(_.validator.validate(providerName, section))
    }
