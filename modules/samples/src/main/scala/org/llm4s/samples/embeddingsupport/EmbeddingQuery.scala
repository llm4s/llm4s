package org.llm4s.samples.embeddingsupport

import org.llm4s.config.Llm4sConfig
import org.llm4s.types.Result

final case class EmbeddingQuery(value: Option[String]) extends AnyVal

object EmbeddingQuery {

  def loadFromEnv(): Result[EmbeddingQuery] =
    Llm4sConfig.embeddingsInputs().map(in => EmbeddingQuery(in.query))
}
