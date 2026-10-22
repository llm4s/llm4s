// scalafix:off DisableSyntax.NoPureConfigDefault
package org.llm4s.samples.embeddingsupport

import com.typesafe.config.{ Config, ConfigObject }
import org.llm4s.error.ConfigurationError
import org.llm4s.llmconnect.config.LocalEmbeddingModels
import org.llm4s.types.Result
import pureconfig.{ ConfigReader as PureConfigReader, ConfigSource }

/** Text-chunking settings for [[EmbeddingExample]]. */
final case class EmbeddingChunkingSettings(
  enabled: Boolean = true,
  size: Int = 1000,
  overlap: Int = 100
)

/** What [[EmbeddingExample]] embeds and the optional query it scores the chunks against. */
final case class EmbeddingInputSettings(
  inputPath: Option[String] = None,
  inputPaths: Option[String] = None,
  query: Option[String] = None
)

/**
 * The embeddings sample's own settings, read from `llm4s.samples.embeddings`.
 *
 * These were `Llm4sConfig.embeddingsChunking()`, `embeddingsInputs()`, `embeddingsUi()`,
 * `experimentalStubsEnabled` and `localEmbeddingModels()` in `llm4s-core`, reading
 * `llm4s.embeddings.*` keys bound by core's `reference.conf`. Only this sample read them, so they
 * moved here with their keys (#2265). The samples' `application.conf` binds the same environment
 * variables as before: `EMBEDDING_INPUT_PATH`, `EMBEDDING_INPUT_PATHS`, `EMBEDDING_QUERY`,
 * `CHUNK_SIZE`, `CHUNK_OVERLAP`, `CHUNKING_ENABLED`, the UI knobs (`MAX_ROWS_PER_FILE`,
 * `TOP_DIMS_PER_ROW`, `GLOBAL_TOPK`, `SHOW_GLOBAL_TOP`, `COLOR`, `TABLE_WIDTH`),
 * `ENABLE_EXPERIMENTAL_STUBS` and `LLM4S_EMBEDDINGS_LOCAL_{IMAGE,AUDIO,VIDEO}_MODEL`.
 *
 * Every key is optional and falls back to the defaults of the settings types; a key that is set
 * but cannot be read (`CHUNK_SIZE=abc`) is a [[org.llm4s.error.ConfigurationError]].
 */
object EmbeddingSampleConfig {

  /** The config path of the sample's settings. */
  val ConfigPath: String = "llm4s.samples.embeddings"

  /** Local model names used for non-text modalities when none are configured. */
  val DefaultLocalModels: LocalEmbeddingModels =
    LocalEmbeddingModels(imageModel = "openclip-vit-b32", audioModel = "wav2vec2-base", videoModel = "timesformer-base")

  /** Reads `chunking.{enabled,size,overlap}`. */
  def chunking(source: ConfigSource = ConfigSource.default): Result[EmbeddingChunkingSettings] = {
    val d = EmbeddingChunkingSettings()
    for {
      section <- sectionOf(source)
      enabled <- read[Boolean](section, "chunking.enabled", d.enabled)
      size    <- read[Int](section, "chunking.size", d.size)
      overlap <- read[Int](section, "chunking.overlap", d.overlap)
    } yield EmbeddingChunkingSettings(enabled = enabled, size = size, overlap = overlap)
  }

  /** Reads `inputPath`, `inputPaths` and `query`; a blank value counts as unset. */
  def inputs(source: ConfigSource = ConfigSource.default): Result[EmbeddingInputSettings] =
    for {
      section    <- sectionOf(source)
      inputPath  <- readText(section, "inputPath")
      inputPaths <- readText(section, "inputPaths")
      query      <- readText(section, "query")
    } yield EmbeddingInputSettings(inputPath = inputPath, inputPaths = inputPaths, query = query)

  /** Reads the `ui` block. */
  def ui(source: ConfigSource = ConfigSource.default): Result[EmbeddingUiSettings] = {
    val d = EmbeddingUiSettings()
    for {
      section       <- sectionOf(source)
      maxRows       <- read[Int](section, "ui.maxRowsPerFile", d.maxRowsPerFile)
      topDims       <- read[Int](section, "ui.topDimsPerRow", d.topDimsPerRow)
      globalTopK    <- read[Int](section, "ui.globalTopK", d.globalTopK)
      showGlobalTop <- read[Boolean](section, "ui.showGlobalTop", d.showGlobalTop)
      colorEnabled  <- read[Boolean](section, "ui.colorEnabled", d.colorEnabled)
      tableWidth    <- read[Int](section, "ui.tableWidth", d.tableWidth)
    } yield EmbeddingUiSettings(
      maxRowsPerFile = maxRows,
      topDimsPerRow = topDims,
      globalTopK = globalTopK,
      showGlobalTop = showGlobalTop,
      colorEnabled = colorEnabled,
      tableWidth = tableWidth
    )
  }

  /** Reads `experimentalStubs`, which defaults to `false`. */
  def experimentalStubs(source: ConfigSource = ConfigSource.default): Result[Boolean] =
    sectionOf(source).flatMap(read[Boolean](_, "experimentalStubs", false))

  /** Reads `localModels.{imageModel,audioModel,videoModel}`, defaulting each to [[DefaultLocalModels]]'s. */
  def localModels(source: ConfigSource = ConfigSource.default): Result[LocalEmbeddingModels] = {
    val d = DefaultLocalModels
    for {
      section <- sectionOf(source)
      image   <- read[String](section, "localModels.imageModel", d.imageModel)
      audio   <- read[String](section, "localModels.audioModel", d.audioModel)
      video   <- read[String](section, "localModels.videoModel", d.videoModel)
    } yield LocalEmbeddingModels(imageModel = image, audioModel = audio, videoModel = video)
  }

  /** The `llm4s.samples.embeddings` section, or `None` when the source has none. */
  private def sectionOf(source: ConfigSource): Result[Option[Config]] =
    source
      .value()
      .left
      .map(failures => ConfigurationError(s"Failed to load $ConfigPath: ${describe(failures)}"))
      .map {
        case root: ConfigObject if root.toConfig.hasPath(ConfigPath) => Some(root.toConfig.getConfig(ConfigPath))
        case _                                                       => None
      }

  private def read[A: PureConfigReader](section: Option[Config], key: String, default: A): Result[A] =
    section match {
      case Some(c) if c.hasPath(key) =>
        ConfigSource
          .fromConfig(c)
          .at(key)
          .load[A]
          .left
          .map(failures => ConfigurationError(s"Invalid $ConfigPath.$key: ${describe(failures)}", List(key)))
      case _ => Right(default)
    }

  private def readText(section: Option[Config], key: String): Result[Option[String]] =
    section match {
      case Some(c) if c.hasPath(key) => read[String](section, key, "").map(v => Option(v.trim).filter(_.nonEmpty))
      case _                         => Right(None)
    }

  private def describe(failures: pureconfig.error.ConfigReaderFailures): String =
    failures.toList.map(_.description).mkString("; ")
}
