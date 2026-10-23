package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable

/**
 * Configuration for a text embedding model, pairing a model identifier with its output vector size.
 *
 * Used by embedding providers and the model dimension registry to resolve the expected
 * dimensionality of embeddings produced by a given model.
 *
 * @param name       Model identifier (e.g. "text-embedding-3-small", "voyage-3-large").
 * @param dimensions Number of dimensions in the embedding vectors produced by this model.
 *
 * Build one with [[EmbeddingModelConfig.apply]] and change it with the `with*` setters; the constructor and `copy`
 * are private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingModelConfig private (
  name: String,
  dimensions: Int
) {

  /** Sets the model identifier. */
  def withName(name: String): EmbeddingModelConfig = copy(name = name)

  /** Sets the number of dimensions in the embedding vectors. */
  def withDimensions(dimensions: Int): EmbeddingModelConfig = copy(dimensions = dimensions)
}

object EmbeddingModelConfig {

  /**
   * Creates an [[EmbeddingModelConfig]].
   *
   * @param name       model identifier
   * @param dimensions number of dimensions in the embedding vectors
   */
  def apply(name: String, dimensions: Int): EmbeddingModelConfig =
    new EmbeddingModelConfig(name, dimensions)
}
