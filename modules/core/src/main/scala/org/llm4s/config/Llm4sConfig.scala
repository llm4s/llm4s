package org.llm4s.config

import org.llm4s.llmconnect.config._
import org.llm4s.metrics.{ MetricsCollector, PrometheusEndpoint }
import org.llm4s.types.Result
import pureconfig.ConfigSource

object Llm4sConfig {

  def provider(): Result[ProviderConfig] =
    org.llm4s.config.ProviderConfigLoader.load(ConfigSource.default)

  def pgSearchIndex(): Result[org.llm4s.rag.permissions.SearchIndex.PgConfig] =
    org.llm4s.config.PgSearchIndexConfigLoader.load(ConfigSource.default)

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

  def embeddings(): Result[(String, EmbeddingProviderConfig)] =
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default)

  def localEmbeddingModels(): Result[LocalEmbeddingModels] =
    org.llm4s.config.EmbeddingsConfigLoader.loadLocalModels(ConfigSource.default)

  def loadEmbeddingsChunking(): Result[EmbeddingsChunkingSettings] = {
    val default = EmbeddingsChunkingSettings(enabled = true, size = 1000, overlap = 100)
    val source  = ConfigSource.default.at("llm4s.embeddings.chunking")

    val size    = source.at("size").load[Int].toOption.getOrElse(default.size)
    val overlap = source.at("overlap").load[Int].toOption.getOrElse(default.overlap)
    val enabled = source.at("enabled").load[Boolean].toOption.getOrElse(default.enabled)

    Right(EmbeddingsChunkingSettings(enabled = enabled, size = size, overlap = overlap))
  }

  def embeddingsChunking(): Result[EmbeddingsChunkingSettings] =
    loadEmbeddingsChunking()

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

  def embeddingsInputs(): Result[EmbeddingsInputSettings] =
    loadEmbeddingsInputs()

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

  def embeddingsUi(): Result[EmbeddingsUiSettings] =
    loadEmbeddingsUiSettings()

  def loadTextEmbeddingModel(): Result[TextEmbeddingModelSettings] =
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default).map { case (provider, cfg) =>
      val p    = provider.toLowerCase
      val dims = ModelDimensionRegistry.getDimension(p, cfg.model)
      TextEmbeddingModelSettings(provider = p, modelName = cfg.model, dimensions = dims)
    }

  def textEmbeddingModel(): Result[TextEmbeddingModelSettings] =
    loadTextEmbeddingModel()

  def experimentalStubsEnabled: Boolean = {
    val source     = ConfigSource.default.at("llm4s.embeddings")
    val configured = source.at("experimentalStubs").load[Boolean].toOption
    configured.getOrElse(false)
  }

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
