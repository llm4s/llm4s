package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * Represents a dense embedding vector produced by an embedding model.
 *
 * @param id       unique identifier for this embedding (e.g. document or chunk ID)
 * @param modality the input modality that was embedded (e.g. text, image)
 * @param model    name of the embedding model that produced this vector
 * @param dim      dimensionality of the embedding vector
 * @param values   raw float values of the embedding vector
 * @param meta     optional key-value metadata associated with this embedding
 *
 * Build one with [[EmbeddingVector.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingVector private (
  id: String,
  modality: Modality,
  model: String,
  dim: Int,
  values: Array[Float],
  meta: Map[String, String]
) {

  /** Sets the identifier. */
  def withId(id: String): EmbeddingVector = copy(id = id)

  /** Sets the embedded modality. */
  def withModality(modality: Modality): EmbeddingVector = copy(modality = modality)

  /** Sets the embedding model name. */
  def withModel(model: String): EmbeddingVector = copy(model = model)

  /** Sets the dimensionality. */
  def withDim(dim: Int): EmbeddingVector = copy(dim = dim)

  /** Sets the vector values. */
  def withValues(values: Array[Float]): EmbeddingVector = copy(values = values)

  /** Sets the metadata. */
  def withMeta(meta: Map[String, String]): EmbeddingVector = copy(meta = meta)
}

object EmbeddingVector {

  /**
   * Creates an [[EmbeddingVector]].
   *
   * @param id       unique identifier for this embedding
   * @param modality the input modality that was embedded
   * @param model    name of the embedding model
   * @param dim      dimensionality of the vector
   * @param values   raw float values
   * @param meta     key-value metadata; empty by default
   */
  def apply(
    id: String,
    modality: Modality,
    model: String,
    dim: Int,
    values: Array[Float],
    meta: Map[String, String] = Map.empty
  ): EmbeddingVector =
    new EmbeddingVector(id, modality, model, dim, values, meta)
}
