package org.llm4s.config

import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.auth.ApiKeyProvider
import org.llm4s.llmconnect.config.*
import org.llm4s.llmconnect.spi.{ ProviderConfigContext, ProviderRegistry }
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*
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
   *  @param sections     the providers block, as read
   *  @param providerName the name of the provider entry to look up
   *  @return `Right(ProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def select(sections: ProviderSections, providerName: String)(using
    ContextWindowResolver,
    ProviderRegistry
  ): Result[ProviderConfig] =
    val trimmed = providerName.trim
    if trimmed.isEmpty then Left(ConfigurationError("Named provider selection requires a non-empty provider name"))
    else
      for
        normalized <- sections.validated(ProviderName(trimmed))
        network    <- sections.network(ProviderName(trimmed))
        config     <- buildConfigFromNamedConfig(trimmed, normalized)
      yield config.withNetwork(network)

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
    sections.sections.keys.map { name =>
      name -> (for
        normalized <- sections.validated(name)
        network    <- sections.network(name)
        config     <- buildConfigFromNamedConfig(name.asName, normalized)
      yield config.withNetwork(network))
    }.toMap

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
    buildConfigFromNamedConfig(name.asName, section)

  private def buildConfigFromNamedConfig(
    providerName: String,
    section: NamedProviderConfig
  )(using ContextWindowResolver, ProviderRegistry): Result[ProviderConfig] =
    // The whole dispatch: find the provider that claims this id and let it build its own config.
    // This used to be a twelve-branch `match` over hard-coded provider names (#1131).
    summon[ProviderRegistry]
      .resolve(section.provider, Some(s"llm4s.providers.$providerName.provider"))
      .flatMap(descriptor =>
        descriptor
          .buildConfig(section, ProviderConfigContext.of(providerName))
          // The timeouts block is read once, for every provider, and every config takes it (#2805).
          .map(_.withTimeouts(section.timeouts))
          .flatMap(withKeyFile(providerName, section, descriptor.configSpec.requiresApiKey, _))
      )

  /**
   * `config` with the section's `apiKeyFile` honoured (#2313). Validation has already read the file into the
   * section's `apiKey`, so every config is built with the key the file holds. A config that can take an
   * `ApiKeyProvider` also gets one over the file, so its client re-reads it when it changes; any other provider
   * that takes an API key keeps the key read at load, and that is logged. A provider that takes no API key
   * (Ollama, Bedrock, Vertex AI) is refused rather than left to ignore the file.
   */
  private def withKeyFile(
    providerName: String,
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
              s"llm4s.providers.$providerName: provider '${section.provider.asString}' reads the key from $file once, " +
                "when the section is loaded; a rotated key is used after the section is loaded again"
            )
            Right(config)
          case Left(_) =>
            Left(
              ConfigurationError(
                s"llm4s.providers.$providerName.apiKeyFile: provider '${section.provider.asString}' takes no API key, " +
                  "so it cannot read one from apiKeyFile - remove it",
                List("apiKeyFile")
              )
            )
