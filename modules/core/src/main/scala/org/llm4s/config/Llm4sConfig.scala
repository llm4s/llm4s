package org.llm4s.config

import org.llm4s.llmconnect.ProviderExchangeLogging
import org.llm4s.llmconnect.config.*
import org.llm4s.metrics.{ MetricsCollector, PrometheusEndpoint }
import org.llm4s.types.Result
import org.llm4s.config.ProvidersConfigModel.{ ProviderName, ProvidersConfig }
import org.llm4s.error.LLMError
import org.llm4s.http.Llm4sHttpClient
import pureconfig.ConfigSource

/**
 * Application-edge configuration loader for LLM4S.
 *
 * Reads all runtime configuration from environment variables and system
 * properties via PureConfig. This is the single authorised entry point for
 * configuration in application and test code — never read `sys.env`,
 * `System.getenv`, or `ConfigFactory.load()` directly.
 *
 * == Provider setup ==
 * Define named providers under `llm4s.providers.<name>` and optionally set
 * `llm4s.providers.provider` to choose the default provider. Then call
 * [[defaultProvider]] or resolve a named provider directly with
 * [[provider(name)*]].
 *
 * Then call [[defaultProvider]] to obtain a
 * [[org.llm4s.llmconnect.config.ProviderConfig]] ready for
 * [[org.llm4s.llmconnect.LLMConnect.getClient]]. Apps that need multiple
 * configured providers can call [[provider(name)*]] directly.
 *
 * @example
 * {{{
 * for {
 *   cfg    <- Llm4sConfig.defaultProvider()
 *   client <- LLMConnect.getClient(cfg)
 *   agent  = new Agent(client)
 *   state  <- agent.run("Hello", ToolRegistry.empty)
 * } yield state
 * }}}
 *
 * @see [[org.llm4s.config.ConfigKeys]] for the full list of recognised
 *      environment variable names.
 */
object Llm4sConfig {

  /**
   * Loads a named provider from `llm4s.providers.<name>`.
   *
   * Useful for applications that need to resolve multiple configured provider
   * instances, including multiple accounts for the same provider type.
   */
  def provider(name: String): Result[ProviderConfig] =
    org.llm4s.config.NamedProviderLoader.load(ConfigSource.default, name)

  def providerConfigs(): Result[(Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig])] =
    org.llm4s.config.NamedProviderLoader.loadProviderConfigs(ConfigSource.default)

  def providerConfigs(
    map: Map[ProviderName, ProvidersConfigModel.NamedProviderConfig]
  ): (Map[ProviderName, LLMError], Map[ProviderName, ProviderConfig]) =
    org.llm4s.config.NamedProviderLoader.getProviderConfigs(map)

  private[config] def provider(source: ConfigSource, name: String): Result[ProviderConfig] =
    org.llm4s.config.NamedProviderLoader.load(source, name)

  /**
   * Loads the full validated named-providers configuration from `llm4s.providers`.
   */
  def providers(): Result[ProvidersConfig] =
    org.llm4s.config.ProvidersConfigLoader.load(ConfigSource.default)

  private[config] def providers(source: ConfigSource): Result[ProvidersConfig] =
    org.llm4s.config.ProvidersConfigLoader.load(source)

  /**
   * Loads the configured default provider name from `llm4s.providers.provider`.
   */
  def defaultProviderName(): Result[ProviderName] =
    providers().flatMap(_.defaultProviderName)

  private[config] def defaultProviderName(source: ConfigSource): Result[ProviderName] =
    providers(source).flatMap(_.defaultProviderName)

  /**
   * Loads the configured default named provider as a runtime [[ProviderConfig]].
   */
  def defaultProvider(): Result[ProviderConfig] =
    defaultProviderName().flatMap(name => provider(name.asName))

  private[config] def defaultProvider(source: ConfigSource): Result[ProviderConfig] =
    defaultProviderName(source).flatMap(name => provider(source, name.asName))

  /**
   * Lists models for the configured default named provider.
   */
  def listModels(): Result[List[DiscoveredModel]] =
    listModels(ConfigSource.default)

  private[config] def listModels(source: ConfigSource): Result[List[DiscoveredModel]] =
    listModels(source, Llm4sHttpClient.create())

  private[config] def listModels(
    source: ConfigSource,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]] =
    for
      defaultName <- defaultProviderName(source)
      models      <- listModels(defaultName.asName, source, httpClient)
    yield models

  /**
   * Lists models for a named provider configured under `llm4s.providers.<name>`.
   */
  def listModels(name: String): Result[List[DiscoveredModel]] =
    listModels(name, ConfigSource.default, Llm4sHttpClient.create())

  private[config] def listModels(
    name: String,
    source: ConfigSource,
    httpClient: Llm4sHttpClient
  ): Result[List[DiscoveredModel]] =
    for
      providers <- providers(source)
      namedProvider <- providers.namedProviders
        .get(ProviderName(name))
        .toRight(org.llm4s.error.ConfigurationError(s"Configured provider '$name' was not found"))
      capabilities <- ProviderCapabilitiesRegistry.forKind(namedProvider.provider)
      lister <- capabilities.modelLister
        .toRight(
          org.llm4s.error.ConfigurationError(
            s"Model discovery is not supported yet for provider '${namedProvider.provider.toString.toLowerCase}'"
          )
        )
      models <- lister.listModels(namedProvider, httpClient)
    yield models

  /**
   * Loads PostgreSQL vector-search index configuration from the current environment.
   *
   * @return the PgConfig, or a [[org.llm4s.error.ConfigurationError]] when
   *         required variables are missing.
   */
  def pgSearchIndex(): Result[org.llm4s.rag.permissions.SearchIndex.PgConfig] =
    org.llm4s.config.PgSearchIndexConfigLoader.load(ConfigSource.default)

  /**
   * Loads tracing configuration from the current environment.
   *
   * Reads `TRACING_MODE` (`langfuse`, `opentelemetry`, `console`, or `none`)
   * and the backend-specific variables (Langfuse keys, OTLP endpoint, etc.).
   *
   * @return the tracing settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when a required backend variable is missing.
   */
  def tracing(): Result[TracingSettings] =
    org.llm4s.config.TracingConfigLoader.load(ConfigSource.default)

  /**
   * Load the metrics configuration.
   *
   * Returns a MetricsCollector and optional PrometheusEndpoint if metrics are enabled.
   * Use MetricsCollector.noop if you want to disable metrics programmatically.
   *
   * @return Result containing (MetricsCollector, Option[PrometheusEndpoint])
   */
  def metrics(): Result[(MetricsCollector, Option[PrometheusEndpoint])] =
    org.llm4s.config.MetricsConfigLoader.load(ConfigSource.default)

  /**
   * Loads provider exchange logging configuration from the current environment.
   *
   * Reads the optional `llm4s.exchangeLogging` section. When absent or disabled,
   * exchange logging remains off. When enabled, a JSONL sink is constructed
   * from a configured directory and writes to a new per-run file.
   */
  // TODO: As part of the wider Llm4sConfig cleanup, accept an optional ConfigSource
  // parameter here instead of hard-wiring ConfigSource.default inside the method body.
  def exchangeLogging(): Result[ProviderExchangeLogging] =
    org.llm4s.config.ProviderExchangeLoggingConfigLoader.load(ConfigSource.default)

  final case class EmbeddingsChunkingSettings(
    enabled: Boolean,
    size: Int,
    overlap: Int
  )

  final case class EmbeddingsInputSettings(
    inputPath: Option[String],
    inputPaths: Option[String],
    query: Option[String]
  )

  final case class EmbeddingsUiSettings(
    maxRowsPerFile: Int,
    topDimsPerRow: Int,
    globalTopK: Int,
    showGlobalTop: Boolean,
    colorEnabled: Boolean,
    tableWidth: Int
  )

  final case class TextEmbeddingModelSettings(
    provider: String,
    modelName: String,
    dimensions: Int
  )

  /**
   * Loads the active embedding provider and its configuration from the current environment.
   *
   * Reads `EMBEDDING_MODEL` in `provider/model` format (e.g.
   * `"openai/text-embedding-3-small"`, `"voyage/voyage-3"`,
   * `"ollama/nomic-embed-text"`). Returns the provider name and typed config.
   *
   * @return a pair of `(providerName, EmbeddingProviderConfig)`, or a
   *         [[org.llm4s.error.ConfigurationError]] when `EMBEDDING_MODEL` is
   *         absent or the provider is unrecognised.
   */
  def embeddings(): Result[(String, EmbeddingProviderConfig)] =
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default)

  /**
   * Loads configuration for locally-available embedding models from the current environment.
   *
   * @return the local model configuration, or a [[org.llm4s.error.ConfigurationError]]
   *         when required variables are missing.
   */
  def localEmbeddingModels(): Result[LocalEmbeddingModels] =
    org.llm4s.config.EmbeddingsConfigLoader.loadLocalModels(ConfigSource.default)

  /**
   * Loads text-chunking settings for the embeddings pipeline from the current environment.
   *
   * Reads `CHUNK_SIZE`, `CHUNK_OVERLAP`, and `CHUNKING_ENABLED`, applying
   * defaults of 1000, 100, and `true` respectively when variables are absent.
   *
   * @return chunking settings; always returns `Right` — missing variables fall
   *         back to defaults rather than failing.
   */
  def loadEmbeddingsChunking(): Result[EmbeddingsChunkingSettings] = {
    val default = EmbeddingsChunkingSettings(enabled = true, size = 1000, overlap = 100)
    val source  = ConfigSource.default.at("llm4s.embeddings.chunking")

    val size    = source.at("size").load[Int].toOption.getOrElse(default.size)
    val overlap = source.at("overlap").load[Int].toOption.getOrElse(default.overlap)
    val enabled = source.at("enabled").load[Boolean].toOption.getOrElse(default.enabled)

    Right(EmbeddingsChunkingSettings(enabled = enabled, size = size, overlap = overlap))
  }

  /** Alias for [[loadEmbeddingsChunking]]. */
  def embeddingsChunking(): Result[EmbeddingsChunkingSettings] =
    loadEmbeddingsChunking()

  /**
   * Loads input path and query settings for the embeddings pipeline from the current environment.
   *
   * Reads `EMBEDDING_INPUT_PATH`, `llm4s.embeddings.inputPaths`, and
   * `EMBEDDING_QUERY`. All fields are optional; returns `Right` with `None`
   * values when variables are absent.
   */
  def loadEmbeddingsInputs(): Result[EmbeddingsInputSettings] = {
    val source = ConfigSource.default.at("llm4s.embeddings")

    val inputPathConf  = source.at("inputPath").load[String].toOption.map(_.trim).filter(_.nonEmpty)
    val inputPathsConf = source.at("inputPaths").load[String].toOption.map(_.trim).filter(_.nonEmpty)
    val queryConf      = source.at("query").load[String].toOption.map(_.trim).filter(_.nonEmpty)

    Right(
      EmbeddingsInputSettings(
        inputPath = inputPathConf,
        inputPaths = inputPathsConf,
        query = queryConf,
      )
    )
  }

  /** Alias for [[loadEmbeddingsInputs]]. */
  def embeddingsInputs(): Result[EmbeddingsInputSettings] =
    loadEmbeddingsInputs()

  /**
   * Loads UI display settings for the embeddings explorer from the current environment.
   *
   * Applies defaults when variables are absent: `maxRowsPerFile=200`,
   * `topDimsPerRow=6`, `globalTopK=10`, `showGlobalTop=false`,
   * `colorEnabled=true`, `tableWidth=120`.
   *
   * @return UI settings; always returns `Right` — missing variables fall back
   *         to defaults rather than failing.
   */
  def loadEmbeddingsUiSettings(): Result[EmbeddingsUiSettings] = {
    val source = ConfigSource.default.at("llm4s.embeddings.ui")

    val maxRowsConf    = source.at("maxRowsPerFile").load[Int].toOption
    val topDimsConf    = source.at("topDimsPerRow").load[Int].toOption
    val globalTopKConf = source.at("globalTopK").load[Int].toOption
    val showTopConf    = source.at("showGlobalTop").load[Boolean].toOption
    val colorOnConf    = source.at("colorEnabled").load[Boolean].toOption
    val tableWidthConf = source.at("tableWidth").load[Int].toOption

    val maxRows    = maxRowsConf.getOrElse(200)
    val topDims    = topDimsConf.getOrElse(6)
    val globalTopK = globalTopKConf.getOrElse(10)
    val showTop    = showTopConf.getOrElse(false)
    val colorOn    = colorOnConf.getOrElse(true)
    val tableWidth = tableWidthConf.getOrElse(120)

    Right(
      EmbeddingsUiSettings(
        maxRowsPerFile = maxRows,
        topDimsPerRow = topDims,
        globalTopK = globalTopK,
        showGlobalTop = showTop,
        colorEnabled = colorOn,
        tableWidth = tableWidth
      )
    )
  }

  /** Alias for [[loadEmbeddingsUiSettings]]. */
  def embeddingsUi(): Result[EmbeddingsUiSettings] =
    loadEmbeddingsUiSettings()

  /**
   * Loads and resolves the active embedding model, including its output dimensions.
   *
   * Reads `EMBEDDING_MODEL` and looks up the known dimension count for the
   * provider/model combination from the bundled dimension registry.
   *
   * @return the resolved settings, or a [[org.llm4s.error.ConfigurationError]]
   *         when `EMBEDDING_MODEL` is absent or unrecognised.
   */
  def loadTextEmbeddingModel(): Result[TextEmbeddingModelSettings] =
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default).flatMap { case (provider, cfg) =>
      val p = provider.toLowerCase
      ModelDimensionRegistry.getDimension(p, cfg.model).map { dims =>
        TextEmbeddingModelSettings(provider = p, modelName = cfg.model, dimensions = dims)
      }
    }

  /** Alias for [[loadTextEmbeddingModel]]. */
  def textEmbeddingModel(): Result[TextEmbeddingModelSettings] =
    loadTextEmbeddingModel()

  /**
   * Returns `true` when experimental embedding stubs are enabled.
   *
   * Controlled by the `llm4s.embeddings.experimentalStubs` config key.
   * Defaults to `false` when the key is absent.
   */
  def experimentalStubsEnabled: Boolean = {
    val source     = ConfigSource.default.at("llm4s.embeddings")
    val configured = source.at("experimentalStubs").load[Boolean].toOption
    configured.getOrElse(false)
  }

  /**
   * Returns the path to a user-supplied model-metadata override file, if configured.
   *
   * When set via `llm4s.modelMetadata.file`, the override file is consulted
   * before the bundled model catalogue when resolving context-window sizes.
   * Returns `None` when the key is absent or blank.
   */
  def modelMetadataOverridePath: Option[String] = {
    val source   = ConfigSource.default.at("llm4s.modelMetadata")
    val fromConf = source.at("file").load[String].toOption.map(_.trim).filter(_.nonEmpty)
    fromConf
  }

  /**
   * Load Brave Search API configuration.
   *
   * Requires BRAVE_SEARCH_API_KEY environment variable.
   *
   * @return Result containing BraveSearchToolConfig with API key and settings
   */
  def loadBraveSearchTool(): Result[BraveSearchToolConfig] =
    org.llm4s.config.ToolsConfigLoader.loadBraveSearchTool(ConfigSource.default)

  /**
   * Load DuckDuckGo Search configuration.
   *
   * No API key required.
   *
   * @return Result containing DuckDuckGoSearchToolConfig with settings
   */
  def loadDuckDuckGoSearchTool(): Result[DuckDuckGoSearchToolConfig] =
    org.llm4s.config.ToolsConfigLoader.loadDuckDuckGoSearchTool(ConfigSource.default)

  /**
   * Load Exa Search API configuration.
   *
   * Requires EXA_API_KEY environment variable.
   *
   * @return Result containing ExaSearchToolConfig with API key and settings
   */
  def loadExaSearchTool(): Result[ExaSearchToolConfig] =
    org.llm4s.config.ToolsConfigLoader.loadExaSearchTool(ConfigSource.default)
}
