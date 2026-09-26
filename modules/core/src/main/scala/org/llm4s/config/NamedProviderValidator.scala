package org.llm4s.config

import org.llm4s.config.ProvidersConfigModel.*
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.spi.{ ProviderDescriptor, ProviderRegistry }
import org.llm4s.types.Result

import java.util.Locale

/**
 * Checks a named provider section against the requirements its provider declares.
 *
 * There is one validator, not one per provider: a provider states what it needs
 * in [[org.llm4s.llmconnect.spi.ProviderConfigSpec]] and this turns that into
 * the error message. Before #1131 each provider had its own
 * `NamedProviderValidator` object in `llm4s-core`, which is the file every new
 * provider had to edit.
 */
private[llm4s] object NamedProviderSectionValidator:

  /**
   * Validates an already-normalised section against `descriptor`.
   *
   *  @param providerName the user's instance name (`llm4s.providers.<name>`), used in error messages
   *  @param descriptor   the provider the section resolved to
   *  @param normalized   the normalised section
   *  @return `Right(normalized)` when every required field is present, or `Left` listing what is missing
   */
  def validate(
    providerName: ProviderName,
    descriptor: ProviderDescriptor,
    normalized: NamedProviderConfig
  ): Result[NamedProviderConfig] =
    if normalized.provider != descriptor.id then
      Left(
        ConfigurationError(
          s"Configured provider '${providerName.asName}' resolved to unexpected provider '${normalized.provider.asString}'"
        )
      )
    else
      val spec = descriptor.configSpec
      val id   = descriptor.id.asString

      // `ProviderId` is already the canonical lowercase spelling, so the env-var prefix is its
      // upper-casing - "openai" -> "OPENAI", as it was under `ProviderKind` - with anything an
      // environment variable name cannot hold made `_`: "openai-compatible" -> "OPENAI_COMPATIBLE".
      val envPrefix     = id.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_")
      val missingFields = Seq.newBuilder[String]

      if spec.requiresApiKey && normalized.apiKey.isEmpty then
        // Named providers resolve from HOCON, not an automatic <PROVIDER>_API_KEY binding, so lead with the
        // conf path (the real fix) and show how to bind an env var explicitly via a HOCON substitution.
        missingFields += s"  - apiKey: set it in llm4s.conf under providers.${providerName.asName}.apiKey (optionally from an env var, e.g. apiKey = $${?${envPrefix}_API_KEY})"

      if spec.requiresBaseUrl && normalized.baseUrl.isEmpty then
        missingFields += s"  - baseUrl: set ${envPrefix}_BASE_URL (${spec.baseUrlExample})"

      if spec.requiresEndpoint && normalized.endpoint.isEmpty then
        missingFields += s"  - endpoint: ${spec.endpointDescription}"

      val errors = missingFields.result()
      if errors.nonEmpty then
        Left(
          ConfigurationError(
            s"Provider '${providerName.asName}' (provider = $id) is missing required fields:\n" + errors
              .mkString("\n")
          )
        )
      else Right(normalized)

/** Normalises a raw named provider section and validates it against its registered provider. */
private[llm4s] object NamedProviderConfigValidator:

  /**
   * Validates a raw provider section by normalising it and checking it against the
   * registered provider it names.
   *
   *  @param providerName the logical name of the provider entry, used in error messages
   *  @param section      the raw unvalidated provider section
   *  @return `Right(NamedProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def validate(
    providerName: ProviderName,
    section: RawNamedProviderSection
  )(using registry: ProviderRegistry): Result[NamedProviderConfig] =
    for
      normalized <- NamedProviderConfigNormalizer.normalize(providerName, section)
      descriptor <- registry.resolve(
        normalized.provider,
        Some(s"llm4s.providers.${providerName.asName}.provider")
      )
      validated <- NamedProviderSectionValidator.validate(providerName, descriptor, normalized)
    yield validated
