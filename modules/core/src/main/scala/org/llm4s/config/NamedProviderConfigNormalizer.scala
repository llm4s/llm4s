package org.llm4s.config

import org.llm4s.error.ConfigurationError
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
  ): Result[NamedProviderConfig] =
    val providerType =
      section.provider.map(_.trim).filter(_.nonEmpty) match
        case None =>
          Left(ConfigurationError(s"Configured provider '${providerName.asName}' is missing required field `provider`"))
        case Some(value) =>
          // An unrecognised provider string is deliberately *not* an error here. Providers are
          // resolved, not enumerated (#1131): whether anything on the classpath handles this id is
          // decided later, by the capabilities lookup, which can name the ids it does know.
          Right(canonicalId(value))

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
      apiVersion = section.apiVersion.map(_.trim).filter(_.nonEmpty)
    )

  /**
   * Accepted alternative spellings, folded onto the canonical id.
   *
   * This is the last place in the codebase that hard-codes provider names for
   * parsing. It moves to `ProviderDescriptor.aliases` when the SPI lands, so
   * that a provider's module declares its own spellings; until then, dropping
   * it would silently break `provider = "google"` and `provider = "vertex"`.
   */
  private def canonicalId(raw: String): ProviderId =
    raw.trim.toLowerCase match
      case "google" => ProviderId("gemini")
      case "vertex" => ProviderId("vertexai")
      case other    => ProviderId(other)
