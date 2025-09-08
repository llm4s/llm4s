package org.llm4s.llmconnect.model

case class EmbeddingResponse(
  vectors: Seq[Seq[Double]],
  metadata: Map[String, String] = Map.empty
)
