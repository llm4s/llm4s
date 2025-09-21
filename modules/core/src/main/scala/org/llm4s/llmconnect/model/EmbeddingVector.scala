package org.llm4s.llmconnect.model

final case class EmbeddingVector(
  id: String,
  modality: Modality,
  model: String,
  dim: Int,
  values: Array[Float],
  meta: Map[String, String] = Map.empty
)
