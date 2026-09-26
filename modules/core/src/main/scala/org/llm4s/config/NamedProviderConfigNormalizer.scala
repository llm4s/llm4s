package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*

/** Converts a `RawNamedProviderSection` into a validated `NamedProviderConfig` by resolving string fields. */
private[config] object NamedProviderConfigNormalizer:

  /**
   * Normalizes a raw provider section into a typed `NamedProviderConfig`.
   *
   *  @param providerName the logical name of the provider entry, used in error messages
   *  @param section      the raw unvalidated provider section to normalize
   *  @return `Right(NamedProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def normalize(
    providerName: ProviderName,
    section: RawNamedProviderSection
  )(using registry: ProviderRegistry): Result[NamedProviderConfig] =
    val providerType =
      section.provider.map(_.trim).filter(_.nonEmpty) match
        case None =>
          Left(ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `provider`"))
        case Some(value) =>
          // An unrecognised provider string is deliberately *not* an error here. Providers are
          // resolved, not enumerated (#1131): whether anything on the classpath handles this id is
          // decided later, by the registry lookup, which can name the ids it does know. Alternative
          // spellings ("google" for Gemini, "vertex" for Vertex AI) are declared by the provider
          // itself as `ProviderDescriptor.aliases`, not hard-coded here.
          Right(registry.canonicalId(value))

    val modelName =
      section.model
        .map(_.trim)
        .filter(_.nonEmpty)
        .toRight(ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `model`"))

    for
      id    <- providerType
      model <- modelName
    yield NamedProviderConfig(
      provider = id,
      model = ModelName(model),
      baseUrl = section.baseUrl.map(_.trim).filter(_.nonEmpty).map(BaseUrl(_)),
      apiKey = section.apiKey.map(_.trim).filter(_.nonEmpty).map(ApiKey(_)),
      organization = section.organization.map(_.trim).filter(_.nonEmpty),
      endpoint = section.endpoint.map(_.trim).filter(_.nonEmpty),
      apiVersion = section.apiVersion.map(_.trim).filter(_.nonEmpty),
      contextWindow = section.contextWindow,
      reserveCompletion = section.reserveCompletion,
      headers = section.headers.getOrElse(Map.empty)
    )
