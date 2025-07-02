package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig

case class EmbeddingRequest(
  input: Seq[String],
  model: EmbeddingModelConfig
)
