package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.http.HttpNetworkSettings
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.*
import org.llm4s.llmconnect.spi.{ ProviderConfigContext, ProviderRegistry }
import org.llm4s.types.ProviderModelTypes.*
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Builds a named provider's `ProviderConfig` from the providers block, as read.
 *
 * Only the section being resolved is validated (#1132): another section's missing API key,
 * unregistered provider or unreadable key fails lookups of that section and nothing else.
 */
private[config] object NamedProviderLoader:

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Builds the `ProviderConfig` for a single named provider from an already-read providers block,
   * validating that section and no other.
   *
   *  @param sections the providers block, as read
   *  @param name     the name of the provider entry to look up
   *  @return `Right(ProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def select(sections: ProviderSections, name: ProviderName)(using
    ContextWindowResolver,
    ProviderRegistry
  ): Result[ProviderConfig] =
    val trimmed = ProviderName(name.asName.trim)
    if trimmed.asName.isEmpty then
      Left(ConfigurationError("Named provider selection requires a non-empty provider name"))
    else
      for
        normalized <- sections.validated(trimmed)
        network    <- sections.network(trimmed)
        config     <- buildConfigFromNamedConfig(trimmed, normalized)
        routed     <- withNetwork(trimmed, normalized, sections.hasOwnHttp(trimmed), network, config)
      yield routed

  /**
   * The provider config of every section of an already-read providers block, each built on its own.
   *
   * Each section is validated and built separately, so one that fails at either step is a `Left` under its
   * name and does not stop the others.
   *
   *  @param sections the providers block, as read
   *  @return each section's config, or the error loading it, by name
   */
  def selectAll(sections: ProviderSections)(using
    ContextWindowResolver,
    ProviderRegistry
  ): Map[ProviderName, Result[ProviderConfig]] =
    sections.sections.keys.map(name => name -> select(sections, name)).toMap

  /**
   * The provider config of one validated section, without any network settings: the caller applies them.
   *
   *  @param name    the section's name, for error messages
   *  @param section the validated section
   */
  def build(name: ProviderName, section: NamedProviderConfig)(using
    ContextWindowResolver,
    ProviderRegistry
  ): Result[ProviderConfig] =
    buildConfigFromNamedConfig(name, section)

  private def buildConfigFromNamedConfig(
    name: ProviderName,
    section: NamedProviderConfig
  )(using ContextWindowResolver, ProviderRegistry): Result[ProviderConfig] =
    // The whole dispatch: find the provider that claims this id and let it build its own config.
    // This used to be a twelve-branch `match` over hard-coded provider names (#1131).
    summon[ProviderRegistry]
      .resolve(section.provider, Some(s"llm4s.providers.${name.asName}.provider"))
      .flatMap(descriptor =>
        descriptor
          .buildConfig(section, ProviderConfigContext.of(name.asName))
          // The timeouts block is read once, for every provider; a config that carries it takes it here.
          .flatMap(withTimeouts(name, section, _))
          .flatMap(withKeyFile(name, section, descriptor.configSpec.requiresApiKey, _))
      )

  /**
   * `config` with the section's `timeouts`. A config that does not take them - its provider makes no timed
   * call - is fine while the section sets none, and an error when it does: they would otherwise be dropped
   * without a word (#2805).
   */
  private def withTimeouts(
    name: ProviderName,
    section: NamedProviderConfig,
    config: ProviderConfig
  ): Result[ProviderConfig] =
    val timed = config.withTimeouts(section.timeouts)
    if timed.timeouts == section.timeouts then Right(timed)
    else
      Left(
        ConfigurationError(
          s"llm4s.providers.${name.asName}.timeouts: provider '${section.provider.asString}' takes no timeouts, " +
            "so the block would be ignored - remove it",
          List("timeouts")
        )
      )

  /**
   * `config` routed by `network`: `llm4s.http` with the section's own `http` block over it. A config that does
   * not take the settings - its provider makes no HTTP call - is an error only for a section with an `http`
   * block of its own; the process-wide block has nothing to route there (#2805).
   */
  private def withNetwork(
    name: ProviderName,
    section: NamedProviderConfig,
    hasOwnHttp: Boolean,
    network: HttpNetworkSettings,
    config: ProviderConfig
  ): Result[ProviderConfig] =
    val routed = config.withNetwork(network)
    if routed.network == network || !hasOwnHttp then Right(routed)
    else
      Left(
        ConfigurationError(
          s"llm4s.providers.${name.asName}.http: provider '${section.provider.asString}' takes no proxy or TLS " +
            "settings, so the block would be ignored - remove it",
          List("http")
        )
      )

  /**
   * `config` with the section's `apiKeyFile` honoured (#2313). Validation has already read the file into the
   * section's `apiKey`, so every config is built with the key the file holds. A config that can take an
   * `ApiKeyProvider` also gets one over the file, so its client re-reads it when it changes; any other provider
   * that takes an API key keeps the key read at load, and that is logged. A provider that takes no API key
   * (Ollama, Bedrock, Vertex AI) is refused rather than left to ignore the file.
   */
  private def withKeyFile(
    name: ProviderName,
    section: NamedProviderConfig,
    takesApiKey: Boolean,
    config: ProviderConfig
  ): Result[ProviderConfig] =
    section.apiKeyFile match
      case None => Right(config)
      case Some(file) =>
        config.withApiKeyProvider(ApiKeyProvider.file(file)) match
          case Right(withFile) => Right(withFile)
          case Left(_) if takesApiKey =>
            logger.info(
              s"llm4s.providers.${name.asName}: provider '${section.provider.asString}' reads the key from $file " +
                "once, when the section is loaded; a rotated key is used after the section is loaded again"
            )
            Right(config)
          case Left(_) =>
            Left(
              ConfigurationError(
                s"llm4s.providers.${name.asName}.apiKeyFile: provider '${section.provider.asString}' takes no API " +
                  "key, so it cannot read one from apiKeyFile - remove it",
                List("apiKeyFile")
              )
            )
