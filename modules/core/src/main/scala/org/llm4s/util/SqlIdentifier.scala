package org.llm4s.util

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

/**
 * Validator for PostgreSQL identifiers (table/index/column).
 * Enforces: start with letter/_, allowed [a-zA-Z0-9_], max 63 chars.
 * Returns a ProcessingError on null/invalid inputs.
 */
object SqlIdentifier {

  private val Pattern = "^[a-zA-Z_][a-zA-Z0-9_]{0,62}$"

  /** Validate a PostgreSQL identifier. */
  def validate(name: String, operation: String): Result[String] =
    if (name == null)
      Left(ProcessingError(operation, "Table name must not be null"))
    else if (!name.matches(Pattern))
      Left(
        ProcessingError(
          operation,
          s"Invalid table name: '$name'. Must start with a letter or underscore, " +
            "contain only letters, digits or underscores, and be at most 63 characters."
        )
      )
    else
      Right(name)
}
