package org.llm4s.llmconnect.config

final case class EmbeddingProviderConfig(
  baseUrl: String,
  model: String,
  apiKey: String
)
