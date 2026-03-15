package org.llm4s.llmconnect.config

/**
 * Configuration for a text embedding model, pairing a model identifier with its output vector size.
 *
 * Used by embedding providers and the model dimension registry to resolve the expected
 * dimensionality of embeddings produced by a given model.
 *
 * @param name       Model identifier (e.g. "text-embedding-3-small", "voyage-3-large").
 * @param dimensions Number of dimensions in the embedding vectors produced by this model.
 */
case class EmbeddingModelConfig(
  name: String,
  dimensions: Int
)
