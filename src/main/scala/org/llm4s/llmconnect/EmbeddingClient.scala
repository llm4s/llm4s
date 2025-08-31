package org.llm4s.llmconnect

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
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

  def fromConfig(config: ConfigReader): EmbeddingClient = {
    val providerName = EmbeddingConfig.activeProvider(config).toLowerCase

    val provider: EmbeddingProvider = providerName match {
      case "openai" => OpenAIEmbeddingProvider(config)
      case "voyage" => VoyageAIEmbeddingProvider(config)
      case unknown =>
        val msg = s"[EmbeddingClient] Unsupported embedding provider: $unknown"
        logger.error(msg)
        throw new RuntimeException(msg)
    }

    logger.info(s"[EmbeddingClient] Initialized with provider: $providerName")
    new EmbeddingClient(provider)
  }
}
