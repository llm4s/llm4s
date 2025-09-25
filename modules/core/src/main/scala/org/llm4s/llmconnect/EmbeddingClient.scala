package org.llm4s.llmconnect

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingProviderConfig }
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

  /**
   * Typed factory: build client from resolved provider name and typed provider config.
   * Avoids reading any additional configuration at runtime.
   */
  def from(provider: String, cfg: EmbeddingProviderConfig): Result[EmbeddingClient] = {
    val p = provider.toLowerCase
    p match {
      case "openai" => Right(new EmbeddingClient(OpenAIEmbeddingProvider.fromConfig(cfg)))
      case "voyage" => Right(new EmbeddingClient(VoyageAIEmbeddingProvider.fromConfig(cfg)))
      case other =>
        Left(
          EmbeddingError(
            code = Some("400"),
            message = s"Unsupported embedding provider: $other",
            provider = "config"
          )
        )
    }
  }

  /**
   * Convenience factory: reads provider + config via ConfigReader and returns a client.
   * Uses the unified loader ConfigReader.Embeddings().
   */
  def fromEnv(): Result[EmbeddingClient] =
    org.llm4s.config.ConfigReader.Embeddings().flatMap { case (provider, cfg) => from(provider, cfg) }
}
