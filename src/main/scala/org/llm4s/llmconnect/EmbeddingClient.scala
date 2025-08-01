package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, EmbeddingError }
import org.llm4s.llmconnect.provider.{ EmbeddingProvider, OpenAIEmbeddingProvider, VoyageAIEmbeddingProvider }
import org.slf4j.LoggerFactory

class EmbeddingClient(provider: EmbeddingProvider) {
  private val logger = LoggerFactory.getLogger(getClass)

  def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse] = {
    logger.info(s"[EmbeddingClient] Embedding input with model ${request.model.name}")
    provider.embed(request)
  }
}

object EmbeddingClient {
  private val logger = LoggerFactory.getLogger(getClass)

  def fromConfig(): EmbeddingClient = {
    val providerName = EmbeddingConfig.activeProvider.toLowerCase

    val provider: EmbeddingProvider = providerName match {
      case "openai" => OpenAIEmbeddingProvider
      case "voyage" => VoyageAIEmbeddingProvider
      case unknown =>
        val msg = s"[EmbeddingClient] Unsupported embedding provider: $unknown"
        logger.error(msg)
        throw new RuntimeException(msg)
    }

    logger.info(s"[EmbeddingClient] Initialized with provider: $providerName")
    new EmbeddingClient(provider)
  }
}
