package org.llm4s.vectorstore

/**
 * Builds `LIKE` patterns that match a string literally.
 *
 * `%` and `_` are wildcards in a `LIKE` pattern, and record ids are caller-supplied, so a prefix
 * such as `a_b-chunk-` used as `prefix + "%"` also matched `axb-chunk-3` (#1318). Pair every pattern
 * from here with `ESCAPE '\\'`.
 */
private[vectorstore] object SqlLikePattern {

  /** The `ESCAPE` clause that goes with [[prefix]]. */
  val EscapeClause: String = "ESCAPE '\\'"

  /** A pattern matching every string that starts with `prefix`, whatever characters it contains. */
  def prefix(prefix: String): String =
    prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%"
}
