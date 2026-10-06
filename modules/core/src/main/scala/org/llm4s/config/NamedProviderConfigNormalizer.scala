package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.{ AuthConfig, IdentitySource }
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*

import java.nio.file.Path
import scala.util.Try

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

    // The identity token is the one auth key core reads itself: exactly one of a file path (made
    // absolute now, so the SDKs, which may resolve a relative path differently, get the same one) or
    // a literal. Every other key is the provider's, and validation decides which are accepted.
    val authConfig: Result[Option[AuthConfig]] =
      section.auth match
        case None => Right(None)
        case Some(raw) =>
          val values = raw.collect { case (key, value) if value.trim.nonEmpty => key -> value.trim }
          val path   = s"llm4s.providers.${providerName.asName}.auth"
          val rest   = values -- AuthConfig.ReservedKeys
          (values.get(AuthConfig.IdentityTokenFileKey), values.get(AuthConfig.IdentityTokenKey)) match
            case (Some(file), None) =>
              Try(Path.of(file).toAbsolutePath.normalize).toEither.left
                .map(e =>
                  ConfigurationError(
                    s"$path.${AuthConfig.IdentityTokenFileKey} is not a valid path on this platform: ${e.getMessage}"
                  )
                )
                .map(absolute => Some(AuthConfig(IdentitySource.File(absolute), rest)))
            case (None, Some(token)) =>
              Right(Some(AuthConfig(IdentitySource.Literal(token), rest)))
            case (None, None) =>
              Left(
                ConfigurationError(
                  s"$path needs ${AuthConfig.IdentityTokenFileKey} (a path to the identity token file, " +
                    s"e.g. a SPIFFE JWT-SVID) or ${AuthConfig.IdentityTokenKey}"
                )
              )
            case (Some(_), Some(_)) =>
              Left(
                ConfigurationError(
                  s"$path sets both ${AuthConfig.IdentityTokenFileKey} and ${AuthConfig.IdentityTokenKey}; set only one"
                )
              )

    for
      id    <- providerType
      model <- modelName
      auth  <- authConfig
    yield NamedProviderConfig(
      provider = id,
      model = ModelName(model),
      baseUrl = section.baseUrl.map(_.trim).filter(_.nonEmpty).map(BaseUrl(_)),
      apiKey = section.apiKey.map(_.trim).filter(_.nonEmpty).map(ApiKey(_)),
      headers = section.headers.getOrElse(Map.empty),
      // Trimmed and kept as read. Which of these the provider accepts is decided by
      // `NamedProviderSectionValidator`, which knows the descriptor; this does not.
      extras = section.extras.collect { case (key, value) if value.trim.nonEmpty => key -> value.trim },
      auth = auth
    )
