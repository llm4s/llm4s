package org.llm4s.vectorstore

/**
 * Builds prefix patterns that match a string literally.
 *
 * `%` and `_` are wildcards in a `LIKE` pattern, and record ids are caller-supplied, so a prefix
 * such as `a_b-chunk-` used as `prefix + "%"` also matched `axb-chunk-3` (#1318). Pair every pattern
 * from [[prefix]] with `ESCAPE '\\'`.
 *
 * SQLite needs [[globPrefix]] instead: its `LIKE` folds ASCII case unless `case_sensitive_like` is
 * on, so even an escaped `Doc-A-chunk-` would match - and delete - `doc-a-chunk-0`. `GLOB` is
 * case-sensitive.
 */
private[vectorstore] object SqlLikePattern {

  /** The `ESCAPE` clause that goes with [[prefix]]. */
  val EscapeClause: String = "ESCAPE '\\'"

  /** A `LIKE` pattern matching every string that starts with `prefix`, whatever characters it contains. */
  def prefix(prefix: String): String =
    prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"

  /**
   * A SQLite `GLOB` pattern matching every string that starts with `prefix`, case-sensitively. `GLOB`
   * has no escape character; a wildcard is matched literally as a one-character class.
   */
  def globPrefix(prefix: String): String =
    prefix.flatMap {
      case c @ ('*' | '?' | '[') => s"[$c]"
      case c                     => c.toString
    } + "*"
}
