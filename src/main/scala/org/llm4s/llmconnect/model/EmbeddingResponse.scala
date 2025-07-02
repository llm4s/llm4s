package org.llm4s.llmconnect.model

/**
 * EmbeddingResponse represents a successful response from an
 * embedding provider such as OpenAI or VoyageAI.
 *
 * @param embeddings --> A sequence of embedding vectors (one per input).
 * @param model --> Optional model name returned by the API.
 * @param objectType --> Optional object type (e.g., "embedding").
 * @param usage --> Optional usage statistics (e.g., token count).
 */
case class EmbeddingResponse(
  vectors: Seq[Seq[Double]],
  metadata: Map[String, String] = Map()
)
