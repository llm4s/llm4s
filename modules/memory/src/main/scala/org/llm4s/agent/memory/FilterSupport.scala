package org.llm4s.agent.memory

/**
 * What the SQL-backed stores need to know about a [[MemoryFilter]] beyond translating it to SQL.
 *
 * A store may only use SQL for a filter when the SQL means exactly what [[MemoryFilter.matches]] means. When it
 * cannot (a `Custom` predicate is arbitrary code), the store reads the rows, applies `matches` itself, and only
 * then applies a limit, a count or a delete. The two are one rule: SQL narrows, `matches` decides.
 */
private[memory] object FilterSupport {

  /** True if a `Custom` predicate appears anywhere in the filter tree: no SQL can express it. */
  def containsCustom(filter: MemoryFilter): Boolean = filter match {
    case _: MemoryFilter.Custom  => true
    case MemoryFilter.And(l, r)  => containsCustom(l) || containsCustom(r)
    case MemoryFilter.Or(l, r)   => containsCustom(l) || containsCustom(r)
    case MemoryFilter.Not(inner) => containsCustom(inner)
    case _                       => false
  }

  /** True if a `MetadataContains` appears anywhere in the filter tree. */
  def containsMetadataContains(filter: MemoryFilter): Boolean = filter match {
    case _: MemoryFilter.MetadataContains => true
    case MemoryFilter.And(l, r)           => containsMetadataContains(l) || containsMetadataContains(r)
    case MemoryFilter.Or(l, r)            => containsMetadataContains(l) || containsMetadataContains(r)
    case MemoryFilter.Not(inner)          => containsMetadataContains(inner)
    case _                                => false
  }

  /** The `LIKE` escape clause that goes with [[escapeLike]]: `LIKE ? ESCAPE '\'`. */
  val LikeEscape: String = "ESCAPE '\\'"

  /**
   * Escape `\`, `%` and `_` so that, in a `LIKE ... ESCAPE '\'` pattern, `text` matches only itself. Without it a
   * `%` or `_` in a filter value is a wildcard, and the filter matches rows it should not.
   */
  def escapeLike(text: String): String =
    text.flatMap {
      case '\\' => "\\\\"
      case '%'  => "\\%"
      case '_'  => "\\_"
      case c    => c.toString
    }
}
