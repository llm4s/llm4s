package org.llm4s.config

import org.llm4s.llmconnect.config._
import org.llm4s.types.Result
import pureconfig.ConfigSource

/**
 * PureConfig-based adapter for loading typed configuration.
 *
 * This is the main entrypoint for loading typed llm4s configuration using the standard
 * Typesafe Config stack (reference.conf, application.conf, env vars, system properties).
 *
 * Prefer using the semantic helpers:
 *   - Llm4sConfig.provider()
 *   - Llm4sConfig.tracing()
 *   - Llm4sConfig.embeddings()
 * in application and sample code.
 */
object Llm4sConfig {

  // ---- Public API: provider config loading ----

  /**
   * High-level helper for provider configuration.
   *
   * This delegates to ProviderConfigLoader to keep this façade slim.
   */
  def provider(): Result[ProviderConfig] =
    org.llm4s.config.ProviderConfigLoader.load(ConfigSource.default)

  // ---- Internal shapes for tracing config ----

  private final case class LangfuseSection(
    url: Option[String],
    publicKey: Option[String],
    secretKey: Option[String],
    env: Option[String],
    release: Option[String],
    version: Option[String]
  )

  // ---- Public API: tracing settings loading ----

  /** High-level helper for tracing settings. */
  def tracing(): Result[TracingSettings] =
    org.llm4s.config.TracingConfigLoader.load(ConfigSource.default)

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

  /** High-level helper for embeddings provider configuration. */
  def embeddings(): Result[(String, EmbeddingProviderConfig)] =
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default)

  /**
   * Load embeddings chunking settings (size/overlap/enabled) from llm4s.embeddings.chunking.
   */
  def loadEmbeddingsChunking(): Result[EmbeddingsChunkingSettings] = {
    val default = EmbeddingsChunkingSettings(enabled = true, size = 1000, overlap = 100)
    val source  = ConfigSource.default.at("llm4s.embeddings.chunking")

    val size    = source.at("size").load[Int].toOption.getOrElse(default.size)
    val overlap = source.at("overlap").load[Int].toOption.getOrElse(default.overlap)
    val enabled = source.at("enabled").load[Boolean].toOption.getOrElse(default.enabled)

    Right(EmbeddingsChunkingSettings(enabled = enabled, size = size, overlap = overlap))
  }

  /** High-level helper for embeddings chunking settings. */
  def embeddingsChunking(): Result[EmbeddingsChunkingSettings] =
    loadEmbeddingsChunking()

  /**
   * Load embeddings input paths and query from llm4s.embeddings.
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

  /** High-level helper for embeddings input settings. */
  def embeddingsInputs(): Result[EmbeddingsInputSettings] =
    loadEmbeddingsInputs()

  /**
   * Load embeddings UI settings (table sizes and flags) from llm4s.embeddings.ui.
   *
   * This mirrors the original sample EmbeddingUiSettings defaults.
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

  /** High-level helper for embeddings UI settings. */
  def embeddingsUi(): Result[EmbeddingsUiSettings] =
    loadEmbeddingsUiSettings()

  /**
   * Load the active text embeddings model (provider + model name + dimensions) using the PureConfig
   * embeddings adapter and the shared ModelDimensionRegistry.
   *
   * This is a typed mirror of the legacy ModelSelector.selectModel(Text, config) path.
  */
  def loadTextEmbeddingModel(): Result[TextEmbeddingModelSettings] =
    org.llm4s.config.EmbeddingsConfigLoader.loadProvider(ConfigSource.default).map { case (provider, cfg) =>
      val p    = provider.toLowerCase
      val dims = ModelDimensionRegistry.getDimension(p, cfg.model)
      TextEmbeddingModelSettings(provider = p, modelName = cfg.model, dimensions = dims)
    }

  /** High-level helper for text embeddings model selection. */
  def textEmbeddingModel(): Result[TextEmbeddingModelSettings] =
    loadTextEmbeddingModel()

  // ---- Internal toggles and registry helpers ----

  /**
   * Experimental stub gate for UniversalEncoder non-text embeddings.
   *
   * When true, UniversalEncoder will enable local/demo stubs for image/audio/video.
   */
  def experimentalStubsEnabled: Boolean = {
    val source      = ConfigSource.default.at("llm4s.embeddings")
    val configured  = source.at("experimentalStubs").load[Boolean].toOption
    configured.getOrElse(false)
  }

  /**
   * Optional override path for model metadata JSON.
   *
   * If set, ModelRegistry will attempt to load additional metadata from this file.
   */
  def modelMetadataOverridePath: Option[String] = {
    val source   = ConfigSource.default.at("llm4s.modelMetadata")
    val fromConf = source.at("file").load[String].toOption.map(_.trim).filter(_.nonEmpty)
    fromConf
  }
}
