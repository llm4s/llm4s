package org.llm4s.llmconnect.model

/**
 * EmbeddingResponse represents a successful response from an
 * embedding provider such as OpenAI or VoyageAI.
 *
 * @param embeddings A sequence of embedding vectors (one per input).
 * @param metadata   Optional metadata such as model name, provider, token count.
 */
case class EmbeddingResponse(
  embeddings: Seq[Seq[Double]],
  metadata: Map[String, String] = Map.empty
)
