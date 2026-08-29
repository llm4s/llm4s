package org.llm4s.agent.memory

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result

import scala.util.{ Failure, Success, Try }

/**
 * pgvector text-format helpers for [[PostgresMemoryStore]].
 *
 * A deliberate duplicate of `org.llm4s.vectorstore.PostgresVectorHelpers`, which moved to
 * `llm4s-rag` in slice 1 of the modularisation programme (llm4s/llm4s#1128). Agent memory
 * is the only thing left in core that reached into `vectorstore`, and it is itself carved
 * out in slice 2 (llm4s/llm4s#1129) - duplicating twenty lines was cheaper than making
 * `llm4s-core` depend on `llm4s-rag`, or blocking slice 1 on slice 2.
 *
 * Delete this file when `PostgresMemoryStore` leaves core, and have `llm4s-memory` use the
 * `vectorstore` version.
 */
private[memory] object PostgresVectorHelpers {

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
