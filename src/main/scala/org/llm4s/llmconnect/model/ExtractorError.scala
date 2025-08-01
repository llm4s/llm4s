package org.llm4s.llmconnect.model

case class ExtractorError(
  message: String,
  `type`: String,
  path: Option[String] = None
)
