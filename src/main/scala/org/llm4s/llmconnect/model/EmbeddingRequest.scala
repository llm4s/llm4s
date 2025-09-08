package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig

/**
 * TEXT-ONLY request.
 * - input: text strings (document, query, etc.)
 */
case class EmbeddingRequest(
  input: Seq[String],
  model: EmbeddingModelConfig,
  metadata: Map[String, String] = Map.empty
)
