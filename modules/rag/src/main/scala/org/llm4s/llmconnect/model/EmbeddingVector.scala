package org.llm4s.llmconnect.model

/**
 * A dense embedding vector with what it was made from, as `FileEmbedder` returns it.
 *
 * The values have the element type of [[EmbeddingResponse.embeddings]], and the vector's dimensionality is
 * their number ([[dim]]). Two vectors with the same fields are equal.
 *
 * @param id       identifier of what was embedded (e.g. a file name and chunk index)
 * @param modality the input modality that was embedded (e.g. text, image)
 * @param model    name of the embedding model that produced this vector
 * @param values   the values of the embedding vector
 * @param meta     key-value metadata associated with this embedding
 *
 * Build one with [[EmbeddingVector.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers.
 */
final case class EmbeddingVector private (
  id: String,
  modality: Modality,
  model: String,
  values: Seq[Double],
  meta: Map[String, String]
) {

  /** The dimensionality of the vector: the number of its values. */
  def dim: Int = values.length

  /** Sets the identifier. */
  def withId(id: String): EmbeddingVector = copy(id = id)

  /** Sets the embedded modality. */
  def withModality(modality: Modality): EmbeddingVector = copy(modality = modality)

  /** Sets the embedding model name. */
  def withModel(model: String): EmbeddingVector = copy(model = model)

  /** Sets the vector values. */
  def withValues(values: Seq[Double]): EmbeddingVector = copy(values = values.toVector)

  /** Sets the metadata. */
  def withMeta(meta: Map[String, String]): EmbeddingVector = copy(meta = meta)
}

object EmbeddingVector {

  /**
   * Creates an [[EmbeddingVector]].
   *
   * @param id       identifier of what was embedded
   * @param modality the input modality that was embedded
   * @param model    name of the embedding model
   * @param values   the vector's values
   * @param meta     key-value metadata; empty by default
   */
  def apply(
    id: String,
    modality: Modality,
    model: String,
    values: Seq[Double],
    meta: Map[String, String] = Map.empty
  ): EmbeddingVector =
    new EmbeddingVector(id, modality, model, values.toVector, meta)
}
