package org.llm4s.llmconnect.model.dbx

final case class CoreHealthReport(
  connectionOk: Boolean,
  schemaOk: Boolean,
  pgvectorVersion: Option[String],
  writeOk: Boolean,
  messages: List[String]
)
