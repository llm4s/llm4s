package org.llm4s.llmconnect.model

case class EmbeddingError(
  code: Option[String],
  message: String,
  provider: String,
  details: Map[String, String] = Map.empty
)
