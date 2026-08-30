package org.llm4s.vectorstore

import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import scala.util.{ Failure, Success, Try }

/**
 * Shared helpers for Postgres pgvector operations: the text codec pgvector uses on the wire,
 * `[0.1,0.2,0.3]`.
 *
 * This is pure Scala over `Array[Float]` - it touches no JDBC type and pulls in no driver -
 * so it lives in `llm4s-core`, at the fully-qualified name it had in 0.4.1, even though the
 * rest of `org.llm4s.vectorstore` ships in `llm4s-rag`.
 *
 * It has two consumers in different modules: `PgVectorStore` and friends in `llm4s-rag`, and
 * `PostgresMemoryStore` in `llm4s-memory-postgres`. Neither module depends on the other, and
 * neither should: making `llm4s-memory-postgres` reach for `llm4s-rag` to get twenty lines
 * would drag Tika, POI, PDFBox, jsoup and the AWS SDK onto the classpath of anyone storing
 * agent memory in Postgres, which is the opposite of what carving memory out was for
 * (llm4s/llm4s#1129). Keeping the one copy in the module both already depend on is the
 * cheaper answer, and it retires the temporary duplicate slice 1 left in
 * `org.llm4s.agent.memory` (llm4s/llm4s#1128).
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
