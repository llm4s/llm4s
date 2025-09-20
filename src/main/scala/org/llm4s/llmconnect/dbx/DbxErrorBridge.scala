package org.llm4s.llmconnect.dbx

import org.llm4s.error._
import org.llm4s.llmconnect.DbxClient
import org.llm4s.llmconnect.model.dbx._
import org.llm4s.types.Result

/**
 * Bridges DBx errors with the LLM4S Result type system
 */
object DbxErrorBridge {

  /**
   * Converts a DbxError to an LLMError for compatibility with the Result type
   */
  def toResult[T](dbxResult: Either[DbxError, T]): Result[T] =
    dbxResult match {
      case Right(value) => Right(value)
      case Left(error)  => Left(toLLMError(error))
    }

  /**
   * Converts a DbxError to the appropriate LLMError type
   */
  def toLLMError(error: DbxError): LLMError = error match {
    case ConfigError(msg) =>
      ConfigurationError(s"DBx configuration error: $msg", List.empty)

    case ConnectionError(msg) =>
      NetworkError(s"DBx connection error: $msg", None, "dbx")

    case PgvectorMissing(msg) =>
      ConfigurationError(s"DBx pgvector missing: $msg", List("pgvector extension"))

    case SchemaError(msg) =>
      ValidationError("schema", s"DBx schema error: $msg")

    case PermissionError(msg) =>
      AuthenticationError("dbx", s"DBx permission error: $msg")

    case WriteError(msg) =>
      ProcessingError("write", s"DBx write error: $msg", None)
  }

  /**
   * Extension methods for DbxClient to return Result types
   */
  implicit class DbxClientOps(client: DbxClient) {
    def initCoreAsResult(): Result[CoreHealthReport] =
      toResult(client.initCore())
  }

  /**
   * Extension methods for Either[DbxError, T]
   */
  implicit class DbxEitherOps[T](either: Either[DbxError, T]) {
    def asResult: Result[T] = toResult(either)
  }
}
