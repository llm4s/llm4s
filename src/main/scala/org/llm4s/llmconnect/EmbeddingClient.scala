package org.llm4s.llmconnect

import org.llm4s.llmconnect.provider.{EmbeddingProvider, OpenAIEmbeddingProvider, VoyageAIEmbeddingProvider}

object EmbeddingClient {
  def fromProvider(providerName: String): EmbeddingProvider = {
    providerName.toLowerCase match {
      case "openai" => OpenAIEmbeddingProvider
      case "voyage" => VoyageAIEmbeddingProvider
      case other    => throw new RuntimeException(s"Unknown provider: $other")
    }
  }
}
