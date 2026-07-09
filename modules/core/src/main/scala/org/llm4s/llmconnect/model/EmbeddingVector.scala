package org.llm4s.llmconnect.model

/**
 * Represents a dense embedding vector produced by an embedding model.
 *
 * @param id       unique identifier for this embedding (e.g. document or chunk ID)
 * @param modality the input modality that was embedded (e.g. text, image)
 * @param model    name of the embedding model that produced this vector
 * @param dim      dimensionality of the embedding vector
 * @param values   raw float values of the embedding vector
 * @param meta     optional key-value metadata associated with this embedding
 */
final case class EmbeddingVector(
  id: String,
  modality: Modality,
  model: String,
  dim: Int,
  values: Array[Float],
  meta: Map[String, String] = Map.empty
)
