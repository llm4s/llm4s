package org.llm4s.samples.embeddingsupport

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.types.Result

final case class EmbeddingRuntimeSettings(
  provider: String,
  chunkingEnabled: Boolean,
  chunkSize: Int,
  chunkOverlap: Int
)

object EmbeddingRuntimeSettings {
  def load(config: ConfigReader): EmbeddingRuntimeSettings = {
    val provider = EmbeddingConfig.activeProvider(config)
    val enabled  = EmbeddingConfig.chunkingEnabled(config)
    val size     = EmbeddingConfig.chunkSize(config)
    val overlap  = EmbeddingConfig.chunkOverlap(config)
    EmbeddingRuntimeSettings(provider, enabled, size, overlap)
  }

  def loadFromEnv(): Result[EmbeddingRuntimeSettings] =
    org.llm4s.config.ConfigReader.LLMConfig().map(load)
}
