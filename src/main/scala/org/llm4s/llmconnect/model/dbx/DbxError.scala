package org.llm4s.llmconnect.model.dbx

sealed trait DbxError { def message: String }
final case class ConfigError(message: String)     extends DbxError
final case class ConnectionError(message: String) extends DbxError
final case class PgvectorMissing(message: String) extends DbxError
final case class SchemaError(message: String)     extends DbxError
final case class PermissionError(message: String) extends DbxError
final case class WriteError(message: String)      extends DbxError
