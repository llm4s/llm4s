package org.llm4s.config

import org.llm4s.annotation.Stable
import org.llm4s.config.ProvidersConfigModel.{ NamedProviderConfig, ProvidersConfig, RawNamedProviderSection }
import org.llm4s.error.ConfigurationError
import org.llm4s.http.{ HttpClientOptions, HttpNetworkSettings, Llm4sHttpClient }
import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.{
  ContextWindowResolver,
  EmbeddingProviderConfig,
  ModelDimensionRegistry,
  ProviderConfig,
  TracingSettings
}
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.{ ModelRegistryConfig, ModelRegistryService }
import org.llm4s.types.ProviderModelTypes.ProviderName
import org.llm4s.types.Result
import pureconfig.ConfigSource

/**
 * The llm4s settings of one configuration: the one implementation of every read.
 *
 * [[Llm4sConfig]]'s methods read the process-wide configuration through an instance of this class, and
 * [[Llm4sConfig.from]] returns one for a `Config` the application supplied. So a method here and the method of
 * the same name on [[Llm4sConfig]] are the same code: the same keys, the same validation (only the section being
 * loaded), the same credential fallback to `llm4s.credentials.<providerId>.apiKey` and the same errors. Instances
 * are immutable and independent, so an application can hold one per tenant, or a test one per case, in the same JVM.
 *
 * == Which providers can be resolved ==
 * Every method that reads `llm4s.providers` or `llm4s.embeddings` takes an implicit
 * [[org.llm4s.llmconnect.spi.ProviderRegistry]], which decides what a `provider = "..."` entry can resolve to.
 * With none in scope it is `ProviderRegistry.default`: everything found on the classpath through
 * `META-INF/services`.
 *
 * @example
 * {{{
 * // appConfig: the com.typesafe.config.Config the application loaded, with an
 * // llm4s.providers block such as  provider = "main", main { provider = "openai", model = "gpt-4o-mini" }
 * for {
 *   settings <- Llm4sConfig.from(appConfig)
 *   registry <- settings.modelRegistryService()
 *   cfg      <- settings.defaultProvider()
 *   client   <- LLMConnect.getClient(cfg)(using registry)
 * } yield client
 * }}}
 */
@Stable
final class Llm4sSettings private[llm4s] (source: ConfigSource) {

  /**
   * The `llm4s.modelRegistry` block: where the model registry is read from. With none of `resourcePath`,
   * `filePath` and `url` set, it is the registry bundled with llm4s.
   *
   * @return the settings, or a [[org.llm4s.error.ConfigurationError]] when the configuration cannot be read or
   *         one of the three keys is set to something that is not a string
   */
  def modelRegistryConfig(): Result[ModelRegistryConfig] =
    for
      resourcePath <- optionalString("llm4s.modelRegistry.resourcePath")
      filePath     <- optionalString("llm4s.modelRegistry.filePath")
      url          <- optionalString("llm4s.modelRegistry.url")
    yield
      val useDefaultResource = resourcePath.isEmpty && filePath.isEmpty && url.isEmpty
      ModelRegistryConfig(
        resourcePath =
          resourcePath.orElse(if useDefaultResource then Some(ModelRegistryConfig.DefaultResourcePath) else None),
        filePath = filePath,
        url = url
      )

  /**
   * The string at `path`, `None` when the key is absent or blank. A configuration that cannot be read, or a
   * value that is not a string, is an error rather than "absent": falling back to the default would hide it.
   */
  private def optionalString(path: String): Result[Option[String]] =
    source.value().left.map(Llm4sSettings.readError).flatMap { root =>
      val segments = path.split('.').toList
      def descend(value: com.typesafe.config.ConfigValue, rest: List[String]): Result[Option[String]] =
        (value, rest) match
          case (obj: com.typesafe.config.ConfigObject, key :: tail) =>
            Option(obj.get(key)).fold[Result[Option[String]]](Right(None))(descend(_, tail))
          case (_, _ :: _) => Right(None)
          case (leaf, Nil) =>
            leaf.unwrapped() match
              case text: String => Right(Some(text.trim).filter(_.nonEmpty))
              case null         => Right(None)
              case _ => Left(ConfigurationError(s"$path must be a string, but is ${leaf.render()}", List(path)))
      descend(root, segments)
    }

  /** The model registry `llm4s.modelRegistry` selects. */
  def modelRegistryService(): Result[ModelRegistryService] =
    modelRegistryConfig().flatMap(ModelRegistryService.fromConfig)

  /**
   * Loads a named provider from `llm4s.providers.<name>`.
   *
   * Useful for applications that need to resolve multiple configured provider
   * instances, including multiple accounts for the same provider type. Only that section is validated.
   */
  // The providers block is read before the model registry, here and below, so that a configuration that cannot
  // be read is reported by the providers loader, whose error links the troubleshooting guide.
  def provider(name: String)(using ProviderRegistry): Result[ProviderConfig] =
    for
      sections <- ProvidersConfigLoader.loadSections(source)
      service  <- modelRegistryService()
      given ContextWindowResolver = ContextWindowResolver(service)
      config <- NamedProviderLoader.select(sections, name)
    yield config

  /**
   * Every section of `llm4s.providers`, each loaded or failed on its own: a section with no key available, or
   * whose provider module is absent, is a `Left` under its name and does not stop the others loading.
   *
   * @return each section's config or error by name; a `Left` only when the providers block itself, or the model
   *         registry, cannot be read
   */
  def providerConfigs()(using ProviderRegistry): Result[Map[ProviderName, Result[ProviderConfig]]] =
    for
      sections <- ProvidersConfigLoader.loadSections(source)
      service  <- modelRegistryService()
      given ContextWindowResolver = ContextWindowResolver(service)
    yield NamedProviderLoader.selectAll(sections)

  /**
   * The provider config of `section`, a section built in code rather than read from `llm4s.providers`, routed by
   * the `llm4s.http` proxy and TLS settings as a configured section with no `http` block of its own is.
   * `llm4s-repl` builds its Ollama session through it.
   */
  private[llm4s] def providerFromSection(name: ProviderName, section: NamedProviderConfig)(using
    ProviderRegistry
  ): Result[ProviderConfig] =
    for
      service <- modelRegistryService()
      given ContextWindowResolver = ContextWindowResolver(service)
      network <- httpNetwork()
      config  <- NamedProviderLoader.build(name, section)
    yield config.withNetwork(network)

  /**
   * The named chat section `name` as validation leaves it - shared credentials applied, extras
   * defaulted - without building the provider's config. Only that section is validated.
   */
  private[llm4s] def providerSection(name: String)(using ProviderRegistry): Result[NamedProviderConfig] =
    ProvidersConfigLoader.loadSections(source).flatMap(_.validated(ProviderName(name)))

  /**
   * The `llm4s.providers` block as read, before any section is validated: the default section's
   * name (`llm4s.providers.provider`, if set) and each section, or the error reading it.
   *
   * For diagnostics that must describe a broken configuration rather than fail on it (the
   * config-policy doctor). A configuration with no `llm4s.providers` block at all reads as no
   * default and no sections; one that cannot be parsed, or whose block has the wrong shape, is
   * a `Left`.
   */
  private[llm4s] def providerBlock()
    : Result[(Option[ProviderName], Map[ProviderName, Result[RawNamedProviderSection]])] =
    source.value() match
      case Left(failures)                                          => Left(Llm4sSettings.readError(failures))
      case Right(_) if source.at("llm4s.providers").value().isLeft => Right((None, Map.empty))
      case Right(_) => ProvidersConfigLoader.loadSections(source).map(s => (s.defaultProvider, s.sections))

  /**
   * The whole `llm4s.providers` block with every section validated, so it fails if any one of them is invalid.
   * Core's own specs check whole-block validation through it; applications use [[providerConfigs]], which reports
   * each section's problem separately.
   */
  private[llm4s] def providers()(using ProviderRegistry): Result[ProvidersConfig] =
    ProvidersConfigLoader.load(source)

  /**
   * Loads the configured default provider name from `llm4s.providers.provider`.
   *
   * Fails if no default is set or no section has that name. No section is validated.
   */
  def defaultProviderName(): Result[ProviderName] =
    ProvidersConfigLoader.loadSections(source).flatMap(_.defaultProviderName)

  /**
   * Loads the configured default named provider as a runtime [[org.llm4s.llmconnect.config.ProviderConfig ProviderConfig]].
   *
   * Only the default section is validated: another section's problem does not fail this.
   */
  // The providers block is read once, and both the default name and its section come from that
  // one result. Only the default section is validated (#1132), so only its deprecated-alias and
  // unknown-key warnings are logged, and a problem in any other section does not fail this.
  def defaultProvider()(using ProviderRegistry): Result[ProviderConfig] =
    for
      sections <- ProvidersConfigLoader.loadSections(source)
      service  <- modelRegistryService()
      given ContextWindowResolver = ContextWindowResolver(service)
      name   <- sections.defaultProviderName
      config <- NamedProviderLoader.select(sections, name.asName)
    yield config

  /**
   * The proxy and TLS settings of the `llm4s.http` block: what every provider client loaded through these
   * settings uses, unless its section's own `http` block overrides it. Pass them to
   * `Llm4sHttpClient.create(HttpClientOptions(network))` for an HTTP client of your own - a tool, a vector
   * store - that should take the same route.
   *
   * A missing trust or key store, or a wrong password, is a `ConfigurationError` naming the key.
   */
  def httpNetwork(): Result[HttpNetworkSettings] =
    HttpNetworkReader.load(source)

  /** Lists models for the configured default named provider. */
  def listModels()(using ProviderRegistry): Result[List[DiscoveredModel]] =
    for
      sections    <- ProvidersConfigLoader.loadSections(source)
      defaultName <- sections.defaultProviderName
      models      <- listModelsThroughNetwork(defaultName.asName, sections)
    yield models

  /** Lists models for a named provider configured under `llm4s.providers.<name>`. */
  def listModels(name: String)(using ProviderRegistry): Result[List[DiscoveredModel]] =
    ProvidersConfigLoader.loadSections(source).flatMap(listModelsThroughNetwork(name, _))

  /** [[listModels()*]] over `httpClient`, which the caller owns. */
  private[llm4s] def listModels(httpClient: Llm4sHttpClient)(using ProviderRegistry): Result[List[DiscoveredModel]] =
    for
      sections    <- ProvidersConfigLoader.loadSections(source)
      defaultName <- sections.defaultProviderName
      models      <- listModels(defaultName.asName, sections, httpClient)
    yield models

  /** [[listModels(name:String)*]] over `httpClient`, which the caller owns. */
  private[llm4s] def listModels(name: String, httpClient: Llm4sHttpClient)(using
    ProviderRegistry
  ): Result[List[DiscoveredModel]] =
    ProvidersConfigLoader.loadSections(source).flatMap(listModels(name, _, httpClient))

  /** Lists `name`'s models with a client routed by the section's proxy and TLS settings, closed afterwards. */
  private def listModelsThroughNetwork(name: String, sections: ProviderSections)(using
    ProviderRegistry
  ): Result[List[DiscoveredModel]] =
    sections
      .network(ProviderName(name))
      .flatMap { network =>
        scala.util.Using.resource(Llm4sHttpClient.create(HttpClientOptions(network)))(listModels(name, sections, _))
      }

  private def listModels(
    name: String,
    sections: ProviderSections,
    httpClient: Llm4sHttpClient
  )(using ProviderRegistry): Result[List[DiscoveredModel]] =
    for
      namedProvider <- sections.validated(ProviderName(name))
      descriptor <- summon[ProviderRegistry].resolve(
        namedProvider.provider,
        Some(s"llm4s.providers.$name.provider")
      )
      lister <- descriptor.modelLister
        .toRight(
          ConfigurationError(
            s"Model discovery is not supported yet for provider '${namedProvider.provider.asString}'"
          )
        )
      models <- lister.listModels(namedProvider, httpClient)
    yield models

  /**
   * Where each named chat section's API key comes from: its own `apiKey`, or its vendor's
   * shared `llm4s.credentials.<providerId>.apiKey`.
   *
   * A section with no key of its own silently uses the shared one - convenient for the default
   * account, and a billing mistake for a section meant for a second account that forgot its
   * key. The config-policy `prod` preset uses this to require explicit keys. Only sections whose
   * provider requires a key are listed; no section is validated.
   */
  def apiKeySources()(using ProviderRegistry): Result[Map[ProviderName, ApiKeySource]] =
    ProvidersConfigLoader.loadSections(source).map(_.apiKeySources)

  /**
   * Loads tracing configuration.
   *
   * Reads `TRACING_MODE` (`console`, `none`, or a backend module's mode such as
   * `langfuse` or `opentelemetry`) and hands the selected mode's
   * `llm4s.tracing.<mode>` block to its backend as `TracingSettings.extras`. The
   * backend's own module binds that block's variables (`LANGFUSE_*` in
   * `llm4s-observability`, `OTEL_*` in `llm4s-observability-otel`) and validates it
   * when `Tracing.fromSettings` builds the tracer.
   *
   * @return the tracing settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when the `llm4s.tracing` tree cannot be parsed, or when the selected
   *         mode's `llm4s.tracing.<mode>` is present but is not an object (an absent
   *         block is fine: the backend applies its defaults).
   */
  def tracing(): Result[TracingSettings] =
    TracingConfigLoader.load(source)

  /**
   * Loads provider exchange logging configuration.
   *
   * Reads the optional `llm4s.exchangeLogging` section. When absent or disabled,
   * exchange logging remains off. When enabled, a JSONL sink is constructed
   * from a configured directory and writes to a new per-run file.
   */
  def exchangeLogging(): Result[ProviderExchangeLogging] =
    ProviderExchangeLoggingConfigLoader.load(source)

  /**
   * Loads the active embedding provider and its configuration.
   *
   * Reads `EMBEDDING_MODEL` in `provider/model` format (e.g.
   * `"openai/text-embedding-3-small"`, `"voyage/voyage-3"`,
   * `"ollama/nomic-embed-text"`). Returns the provider name and typed config.
   *
   * The provider is resolved through the given
   * [[org.llm4s.llmconnect.spi.ProviderRegistry]], so an embedding provider
   * supplied by a module - or registered explicitly - is configurable here
   * without `llm4s-core` knowing it exists.
   *
   * @return a pair of `(providerName, EmbeddingProviderConfig)`, or a
   *         [[org.llm4s.error.ConfigurationError]] when `EMBEDDING_MODEL` is
   *         absent or the provider is unrecognised.
   */
  def embeddings()(using ProviderRegistry): Result[(String, EmbeddingProviderConfig)] =
    EmbeddingsConfigLoader.loadProvider(source)

  /**
   * [[embeddings()*]] for the model `modelSpec` names, `provider/model`, instead of the one
   * `llm4s.embeddings.model` selects: the provider's `llm4s.embeddings.<provider>` block and its
   * key are read as they are for that one. `llm4s-java-api`'s `Llm4s.createEmbeddingClient` loads
   * through it.
   */
  private[llm4s] def embeddingsFor(modelSpec: String)(using
    ProviderRegistry
  ): Result[(String, EmbeddingProviderConfig)] =
    EmbeddingsConfigLoader.loadProviderFor(source, modelSpec)

  /**
   * Loads and resolves the active embedding model, including its output dimensions.
   *
   * Reads `EMBEDDING_MODEL` and looks up the known dimension count for the
   * provider/model combination from the bundled dimension registry.
   *
   * Resolves the provider through [[embeddings]], and so through the given
   * [[org.llm4s.llmconnect.spi.ProviderRegistry]]: a provider that is
   * configurable there is configurable here, by the same registry.
   *
   * @return the resolved settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when `EMBEDDING_MODEL` is absent or unrecognised.
   */
  def textEmbeddingModel()(using ProviderRegistry): Result[Llm4sConfig.TextEmbeddingModelSettings] =
    embeddings().flatMap { case (provider, cfg) =>
      val p = provider.toLowerCase(java.util.Locale.ROOT)
      ModelDimensionRegistry.getDimension(p, cfg.model).map { dims =>
        Llm4sConfig.TextEmbeddingModelSettings(provider = p, modelName = cfg.model, dimensions = dims)
      }
    }
}

private[llm4s] object Llm4sSettings {

  /** A configuration that could not be read, as one line. */
  private def readError(failures: pureconfig.error.ConfigReaderFailures): ConfigurationError =
    ConfigurationError(failures.prettyPrint().linesIterator.map(_.trim).filter(_.nonEmpty).mkString(" "))
}
