package org.llm4s.llmconnect.utils.dbx

import org.llm4s.llmconnect.model.dbx.{ DbxError, SchemaError }

object SqlSafetyUtils {

  /**
   * Validates that a SQL identifier (table, column, schema name) is safe to use.
   * Only allows alphanumeric characters, underscores, and dollar signs.
   * This prevents SQL injection when identifiers must be used in dynamic SQL.
   */
  def validateIdentifier(identifier: String): Either[DbxError, String] =
    if (identifier == null || identifier.trim.isEmpty) {
      Left(SchemaError("Identifier cannot be null or empty"))
    } else if (!isValidSqlIdentifier(identifier)) {
      Left(
        SchemaError(
          s"Invalid SQL identifier: '$identifier'. Only alphanumeric characters, underscores, and dollar signs allowed."
        )
      )
    } else {
      Right(identifier)
    }

  /**
   * Validates multiple SQL identifiers
   */
  def validateIdentifiers(identifiers: String*): Either[DbxError, Unit] =
    identifiers.foldLeft[Either[DbxError, Unit]](Right(())) { (acc, id) =>
      acc match {
        case Left(err) => Left(err)
        case Right(_)  => validateIdentifier(id).map(_ => ())
      }
    }

  /**
   * Escapes and quotes a SQL identifier for safe usage.
   * Double-quotes any internal quotes and wraps in double quotes.
   */
  def quoteIdentifier(identifier: String): String =
    "\"" + identifier.replace("\"", "\"\"") + "\""

  /**
   * Safely formats a schema-qualified table name
   */
  def qualifiedTableName(schema: String, table: String): Either[DbxError, String] =
    for {
      validSchema <- validateIdentifier(schema)
      validTable  <- validateIdentifier(table)
    } yield s"${quoteIdentifier(validSchema)}.${quoteIdentifier(validTable)}"

  private def isValidSqlIdentifier(identifier: String): Boolean = {
    // PostgreSQL identifier rules:
    // - Must start with letter or underscore
    // - Can contain letters, digits, underscores, dollar signs
    // - Max 63 characters (we'll be more restrictive)
    val pattern = "^[a-zA-Z_][a-zA-Z0-9_$]{0,62}$".r
    pattern.matches(identifier)
  }
}
