package org.llm4s.samples.embeddingsupport

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.EmbeddingConfig

final case class EmbeddingQuery(value: Option[String]) extends AnyVal

object EmbeddingQuery {
  def load(config: ConfigReader): EmbeddingQuery =
    EmbeddingQuery(EmbeddingConfig.query(config))

  def loadFromEnv(): org.llm4s.types.Result[EmbeddingQuery] =
    org.llm4s.config.ConfigReader.LLMConfig().map(load)
}
