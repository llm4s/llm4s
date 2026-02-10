package org.llm4s.agent.memory

import scala.util.Try

/**
 * Shared helpers for Postgres pgvector operations.
 */
object PostgresVectorHelpers {

  def embeddingToString(embedding: Array[Float]): String =
    embedding.mkString("[", ",", "]")

  def stringToEmbedding(s: String): Array[Float] =
    if (s == null || s.isEmpty) Array.empty
    else {
      val cleaned = s.stripPrefix("[").stripSuffix("]")
      if (cleaned.isEmpty) Array.empty
      else Try(cleaned.split(",").map(_.trim.toFloat)).getOrElse(Array.empty)
    }
}
