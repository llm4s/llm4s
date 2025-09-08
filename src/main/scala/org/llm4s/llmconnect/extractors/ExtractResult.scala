package org.llm4s.llmconnect.extractors

case class ExtractResult(
  content: String, // UTF-8 text
  mimeType: Option[String],
  metadata: Map[String, String]
)
