package org.llm4s.config

import org.llm4s.llmconnect.config._
import org.llm4s.metrics.{ MetricsCollector, PrometheusEndpoint }
import org.llm4s.types.Result
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
 * Set `LLM_MODEL` to `provider/model` (e.g. `"openai/gpt-4o"`,
 * `"anthropic/claude-sonnet-4-5-latest"`, `"gemini/gemini-2.0-flash"`) and
 * the corresponding API key environment variable. Then call [[provider]] to
 * obtain a [[org.llm4s.llmconnect.config.ProviderConfig]] ready for
 * [[org.llm4s.llmconnect.LLMConnect.getClient]].
 *
 * @example
 * {{{
 * for {
 *   cfg    <- Llm4sConfig.provider()
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
   * Loads LLM provider configuration from the current environment.
   *
   * Reads `LLM_MODEL` (format: `provider/model`) and the matching
   * credential variables (`OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, etc.) to
   * build a typed [[org.llm4s.llmconnect.config.ProviderConfig]].
   *
   * @return the provider configuration, or a
   *         [[org.llm4s.error.ConfigurationError]] when required variables are
   *         missing or the provider prefix is unrecognised.
   */
  def provider(): Result[ProviderConfig] =
    org.llm4s.config.ProviderConfigLoader.load(ConfigSource.default)

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
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default).map { case (provider, cfg) =>
      val p    = provider.toLowerCase
      val dims = ModelDimensionRegistry.getDimension(p, cfg.model)
      TextEmbeddingModelSettings(provider = p, modelName = cfg.model, dimensions = dims)
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
