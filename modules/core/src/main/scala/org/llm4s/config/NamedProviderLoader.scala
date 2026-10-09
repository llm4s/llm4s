package org.llm4s.config

import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.llmconnect.config.*
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.*
import pureconfig.ConfigSource

/**
 * Loads and resolves a named provider's `ProviderConfig` from a `ConfigSource`.
 *
 * Only the section being resolved is validated (#1132): another section's missing API key,
 * unregistered provider or unreadable key fails lookups of that section and nothing else.
 */
private[config] object NamedProviderLoader:

  /**
   * Loads the `ProviderConfig` for a single named provider from the given config source.
   *
   *  @param source       the PureConfig source to read from
   *  @param providerName the name of the provider entry to look up
   *  @return `Right(ProviderConfig)` on success, or `Left` with a `ConfigurationError`
   */
  def load(source: ConfigSource, providerName: String)(using
    ContextWindowResolver,
    ProviderRegistry
  ): Result[ProviderConfig] =
    if providerName.trim.isEmpty then
      Left(ConfigurationError("Named provider selection requires a non-empty provider name"))
    else ProvidersConfigLoader.loadSections(source).flatMap(select(_, providerName))

  /**
   * Builds the `ProviderConfig` for a single named provider from an already-read providers block,
   * validating that section and no other.
   *
   * Callers that need the block for something else as well - the default provider's name, say -
   * read it once and pass it here, rather than calling [[load]] and so reading it a second time.
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
        config     <- buildConfigFromNamedConfig(trimmed, normalized)
      yield config

  /**
   * Loads all named provider configs from the given config source, returning errors and successes separately.
   *
   * Each section is read, validated and built on its own, so one that fails at any of those
   * steps is reported in the error map and does not stop the others loading. Only a providers
   * block that cannot be read at all is a `Left`.
   *
   *  @param source the PureConfig source to read from
   *  @return `Right` of a pair: failed entries mapped to their errors, and successful entries mapped to their configs
   */
  def loadProviderConfigs(
    source: ConfigSource
  )(using
    ContextWindowResolver,
    ProviderRegistry
  ): Result[(Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig])] =
    ProvidersConfigLoader.loadSections(source).map { sections =>
      val results = sections.sections.keys.toList.map { name =>
        name -> sections.validated(name).flatMap(buildConfigFromNamedConfig(name.asName, _))
      }
      (
        results.collect { case (name, Left(error)) => name -> error }.toMap,
        results.collect { case (name, Right(config)) => name -> config }.toMap
      )
    }

  /**
   * Converts a map of validated named provider configs into separate error and success maps.
   *
   *  @param namedProviders the validated named providers to process
   *  @return a pair: provider names mapped to build errors, and provider names mapped to resolved `ProviderConfig`s
   */
  def getProviderConfigs(
    namedProviders: Map[ProviderName, NamedProviderConfig]
  )(using
    ContextWindowResolver,
    ProviderRegistry
  ): (Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig]) =
    namedProviders.toList.foldLeft((Map.empty[ProviderName, LLMError], Map.empty[ProviderName, ProviderConfig]))(
      (x, y) =>
        buildConfigFromNamedConfig(y._1.asName, y._2).fold(
          (error: LLMError) =>
            val kv = (y._1, error)
            (x._1 + kv, x._2)
          ,
          (providerConfig: ProviderConfig) =>
            val kv: (ProviderName, ProviderConfig) = (y._1, providerConfig)
            (x._1, x._2 + kv)
        )
    )

  private def buildConfigFromNamedConfig(
    providerName: String,
    section: NamedProviderConfig
  )(using ContextWindowResolver, ProviderRegistry): Result[ProviderConfig] =
    // The whole dispatch: find the provider that claims this id and let it build its own config.
    // This used to be a twelve-branch `match` over hard-coded provider names (#1131).
    summon[ProviderRegistry]
      .resolve(section.provider, Some(s"llm4s.providers.$providerName.provider"))
      .flatMap(_.buildConfig(providerName, section))
      // The timeouts block is read once, for every provider; a config that carries it takes it here.
      .map(_.withTimeouts(section.timeouts))
