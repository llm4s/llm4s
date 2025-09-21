package org.llm4s.llmconnect

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.EmbeddingConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse, EmbeddingVector }
import org.llm4s.llmconnect.provider.{ EmbeddingProvider, OpenAIEmbeddingProvider, VoyageAIEmbeddingProvider }
import org.llm4s.llmconnect.encoding.UniversalEncoder
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.nio.file.Path

class EmbeddingClient(provider: EmbeddingProvider) {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Text embeddings via the configured HTTP provider. */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    logger.debug(s"[EmbeddingClient] Embedding with model=${request.model.name}, inputs=${request.input.size}")
    provider.embed(request)
  }

  /** Unified API to encode any supported file into vectors. */
  def encodePath(path: Path): Result[Seq[EmbeddingVector]] =
    UniversalEncoder.encodeFromPath(path, this)
}

object EmbeddingClient {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Legacy factory (back-compat): throws on unsupported provider.
   * Prefer [[fromConfigEither]] in new code to avoid exceptions on misconfig.
   */
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

  /**
   * Safe factory: returns Either instead of throwing on misconfiguration.
   * Useful for samples/CLIs where we want a clean error path.
   */
  def fromConfigEither(config: ConfigReader): Result[EmbeddingClient] = {
    val providerName = EmbeddingConfig.activeProvider(config).toLowerCase
    val providerOpt: Option[EmbeddingProvider] = providerName match {
      case "openai" => Some(OpenAIEmbeddingProvider(config))
      case "voyage" => Some(VoyageAIEmbeddingProvider(config))
      case _        => None
    }

    providerOpt match {
      case Some(p) =>
        logger.info(s"[EmbeddingClient] Initialized with provider: $providerName")
        Right(new EmbeddingClient(p))
      case None =>
        Left(
          EmbeddingError(
            code = Some("400"),
            message = s"Unsupported embedding provider: $providerName",
            provider = "config"
          )
        )
    }
  }
}
