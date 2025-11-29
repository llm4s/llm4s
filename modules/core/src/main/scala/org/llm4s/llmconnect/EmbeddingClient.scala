package org.llm4s.llmconnect
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, EmbeddingProviderConfig }
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

  /** Unified API to encode any supported file into vectors, given text model + chunking. */
  def encodePath(
    path: Path,
    textModel: EmbeddingModelConfig,
    chunking: UniversalEncoder.TextChunkingConfig
  ): Result[Seq[EmbeddingVector]] =
    encodePath(path, textModel, chunking, experimentalStubsEnabled = false)

  /** Unified API to encode any supported file with an explicit experimental-stubs toggle. */
  def encodePath(
    path: Path,
    textModel: EmbeddingModelConfig,
    chunking: UniversalEncoder.TextChunkingConfig,
    experimentalStubsEnabled: Boolean
  ): Result[Seq[EmbeddingVector]] =
    UniversalEncoder.encodeFromPath(path, this, textModel, chunking, experimentalStubsEnabled)
}

object EmbeddingClient {
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

}
