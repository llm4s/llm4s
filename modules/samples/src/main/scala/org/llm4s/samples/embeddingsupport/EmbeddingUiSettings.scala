package org.llm4s.samples.embeddingsupport

import org.llm4s.config.ConfigReader

final case class EmbeddingUiSettings(
  maxRowsPerFile: Int = 200,
  topDimsPerRow: Int = 6,
  globalTopK: Int = 10,
  showGlobalTop: Boolean = false,
  colorEnabled: Boolean = true,
  tableWidth: Int = 120
)

object EmbeddingUiSettings {
  def load(config: ConfigReader): EmbeddingUiSettings = {
    def getInt(key: String, default: Int): Int =
      config.get(key).flatMap(s => scala.util.Try(s.toInt).toOption).getOrElse(default)

    val maxRows    = getInt("MAX_ROWS_PER_FILE", 200)
    val topDims    = getInt("TOP_DIMS_PER_ROW", 6)
    val globalTopK = getInt("GLOBAL_TOPK", 10)
    val showTop    = config.get("SHOW_GLOBAL_TOP").exists(_.trim.equalsIgnoreCase("true"))
    val colorOn    = config.get("COLOR").forall(_.trim.equalsIgnoreCase("true")) // default ON
    val tableWidth = getInt("TABLE_WIDTH", 120)

    EmbeddingUiSettings(maxRows, topDims, globalTopK, showTop, colorOn, tableWidth)
  }

  def loadFromEnv(): org.llm4s.types.Result[EmbeddingUiSettings] =
    org.llm4s.config.ConfigReader.LLMConfig().map(load)
}
