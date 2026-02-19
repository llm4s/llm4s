package org.llm4s.vectorstore

import scala.util.Try

/**
 * Utility for parsing embedding strings into float arrays.
 * Shared by PgVectorStore and PgSearchIndex.
 *
 * Empty embeddings (`[]`) are considered invalid as zero-dimensional vectors
 * have no meaningful similarity. For RAG sentinel values, use content field instead.
 */
private[llm4s] object EmbeddingParser {

  /** Maximum embedding dimension to prevent DoS attacks via massive vectors */
  val MAX_EMBEDDING_DIM = 16384

  /**
   * Parse embedding string to float array with validation.
   * Returns None if parsing fails.
   *
   * Validation rules:
   * - Must be bracketed: `[...]`
   * - No empty embeddings: `[]` returns None (zero dimensions invalid)
   * - Max dimension: 16384 floats (prevent OOM attacks)
   * - All tokens must parse as valid floats
   *
   * @param s Embedding string in format "[0.1,0.2,0.3]"
   * @return Some(array) if valid, None if corrupt/unparseable/oversized/empty
   */
  def parse(s: String): Option[Array[Float]] = {
    if (s == null || s.isEmpty) return None
    if (!s.startsWith("[") || !s.endsWith("]")) return None
    val cleaned = s.substring(1, s.length - 1)
    if (cleaned.isEmpty) None // Zero dimensions invalid for similarity search
    else {
      // Count separators before splitting to prevent DoS via massive allocation
      val commaCount = cleaned.count(_ == ',')
      if (commaCount >= MAX_EMBEDDING_DIM) None // Would produce > MAX_EMBEDDING_DIM elements
      else Try(cleaned.split(",").map(_.trim.toFloat)).toOption
    }
  }
}
