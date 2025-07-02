package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.provider.{ EmbeddingProvider, OpenAIEmbeddingProvider, VoyageAIEmbeddingProvider }

object EmbeddingClient {
  def fromConfig(): EmbeddingProvider =
    EmbeddingConfig.activeProvider match {
      case "openai" => OpenAIEmbeddingProvider
      case "voyage" => VoyageAIEmbeddingProvider
      case other    => throw new RuntimeException(s"Unknown embedding provider: $other")
    }
}
