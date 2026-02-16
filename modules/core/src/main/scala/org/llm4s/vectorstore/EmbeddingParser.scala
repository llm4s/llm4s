package org.llm4s.vectorstore

import scala.util.Try

/**
 * Utility for parsing embedding strings into float arrays.
 * Shared by PgVectorStore and PgSearchIndex.
 */
private[llm4s] object EmbeddingParser {

  /**
   * Parse embedding string to float array.
   * Returns None if parsing fails.
   *
   * @param s Embedding string in format "[0.1,0.2,0.3]"
   * @return Some(array) if valid, None if corrupt/unparseable
   */
  def parse(s: String): Option[Array[Float]] = {
    if (s == null || s.isEmpty) return None
    if (!s.startsWith("[") || !s.endsWith("]")) return None
    val cleaned = s.substring(1, s.length - 1)
    if (cleaned.isEmpty) None
    else Try(cleaned.split(",").map(_.trim.toFloat)).toOption
  }
}
