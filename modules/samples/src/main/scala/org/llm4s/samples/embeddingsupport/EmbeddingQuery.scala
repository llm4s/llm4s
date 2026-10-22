package org.llm4s.samples.embeddingsupport

import org.llm4s.types.Result

final case class EmbeddingQuery(value: Option[String]) extends AnyVal

object EmbeddingQuery {

  /** The query `llm4s.samples.embeddings.query` (`EMBEDDING_QUERY`) sets, if any. */
  def loadFromEnv(): Result[EmbeddingQuery] =
    EmbeddingSampleConfig.inputs().map(in => EmbeddingQuery(in.query))
}
