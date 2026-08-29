package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import scala.util.{ Failure, Success, Try }

/**
 * Shared helpers for Postgres pgvector operations.
 */
object PostgresVectorHelpers {

  def embeddingToString(embedding: Array[Float]): String =
    embedding.mkString("[", ",", "]")

  def stringToEmbedding(s: String): Result[Array[Float]] =
    if (s == null || s.isEmpty) {
      Right(Array.empty[Float])
    } else {
      val cleaned = s.stripPrefix("[").stripSuffix("]")
      if (cleaned.isEmpty) {
        Right(Array.empty[Float])
      } else {
        Try(cleaned.split(",").map(_.trim.toFloat)) match {
          case Success(arr) => Right(arr)
          case Failure(e) =>
            Left(ProcessingError("vector-parser", s"Failed to parse vector embedding: '$s'", Some(e)))
        }
      }
    }
}
