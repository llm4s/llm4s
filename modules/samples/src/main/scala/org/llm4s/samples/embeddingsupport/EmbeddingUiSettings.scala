package org.llm4s.samples.embeddingsupport

final case class EmbeddingUiSettings(
  maxRowsPerFile: Int = 200,
  topDimsPerRow: Int = 6,
  globalTopK: Int = 10,
  showGlobalTop: Boolean = false,
  colorEnabled: Boolean = true,
  tableWidth: Int = 120
)
