package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.{
  EmbeddingProvider,
  JinaEmbeddingProvider,
  OllamaEmbeddingProvider,
  OpenAIEmbeddingProvider,
  VoyageAIEmbeddingProvider
}
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.Tracing
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

class EmbeddingClient(
  provider: EmbeddingProvider,
  tracer: Option[Tracing] = None,
  operation: String = "embedding"
)(using service: ModelRegistryService) {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Text embeddings via the configured HTTP provider. */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    logger.debug(s"[EmbeddingClient] Embedding with model=${request.model.name}, inputs=${request.input.size}")

    val result = provider.embed(request)

    // Emit trace events if tracing is enabled and we got a response with usage
    result.foreach { response =>
      tracer.foreach { t =>
        response.usage.foreach { usage =>
          // Emit embedding usage event
          t.traceEmbeddingUsage(
            usage = usage,
            model = request.model.name,
            operation = operation,
            inputCount = request.input.size
          )

          // Try to calculate and emit cost
          service.lookup(request.model.name).foreach { meta =>
            meta.pricing.estimateCost(usage.promptTokens, 0).foreach { cost =>
              t.traceCost(
                costUsd = cost,
                model = request.model.name,
                operation = operation,
                tokenCount = usage.totalTokens,
                costType = "embedding"
              )
            }
          }
        }
      }
    }

    result
  }

  /** Multimodal embeddings via the configured HTTP provider. */
  def embedMultimodal(
    request: org.llm4s.llmconnect.model.MultimediaEmbeddingRequest
  ): Result[EmbeddingResponse] = {
    logger.debug(
      s"[EmbeddingClient] Multimodal embedding with model=${request.model.name}, modality=${request.modality}"
    )

    val result = provider.embedMultimodal(request)

    // Emit trace events if tracing is enabled and we got a response with usage
    result.foreach { response =>
      tracer.foreach { t =>
        response.usage.foreach { usage =>
          // Emit embedding usage event
          t.traceEmbeddingUsage(
            usage = usage,
            model = request.model.name,
            operation = operation,
            inputCount = request.inputs.size
          )

          // Try to calculate and emit cost
          service.lookup(request.model.name).foreach { meta =>
            meta.pricing.estimateCost(usage.promptTokens, 0).foreach { cost =>
              t.traceCost(
                costUsd = cost,
                model = request.model.name,
                operation = operation,
                tokenCount = usage.totalTokens,
                costType = "embedding"
              )
            }
          }
        }
      }
    }

    result
  }

  /** Create a new client with tracing enabled. */
  def withTracing(tracer: Tracing): EmbeddingClient =
    new EmbeddingClient(provider, Some(tracer), operation)

  /** Create a new client with a specific operation label for tracing. */
  def withOperation(op: String): EmbeddingClient =
    new EmbeddingClient(provider, tracer, op)
}

object EmbeddingClient {

  /**
   * Typed factory: build client from resolved provider name and typed provider config.
   * Avoids reading any additional configuration at runtime.
   */
  def from(provider: String, cfg: EmbeddingProviderConfig)(using ModelRegistryService): Result[EmbeddingClient] = {
    val p = provider.toLowerCase
    p match {
      case "openai" => Right(new EmbeddingClient(OpenAIEmbeddingProvider.fromConfig(cfg)))
      case "voyage" => Right(new EmbeddingClient(VoyageAIEmbeddingProvider.fromConfig(cfg)))
      case "jina"   => Right(new EmbeddingClient(JinaEmbeddingProvider.fromConfig(cfg)))
      case "ollama" => Right(new EmbeddingClient(OllamaEmbeddingProvider.fromConfig(cfg)))
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
