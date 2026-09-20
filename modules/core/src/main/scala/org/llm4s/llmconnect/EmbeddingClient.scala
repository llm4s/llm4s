package org.llm4s.llmconnect

import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.ProviderRegistry
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
   *
   * `provider` is resolved through the [[org.llm4s.llmconnect.spi.ProviderRegistry]],
   * so an embedding provider that ships in its own module is reachable here
   * with nothing in `llm4s-core` edited — see
   * [[org.llm4s.llmconnect.spi.EmbeddingProviderDescriptor]]. Applications that
   * register providers explicitly pass their own registry:
   *
   * {{{
   * given ProviderRegistry = ProviderRegistry.default.withEmbeddingProvider(JinaEmbeddings)
   * EmbeddingClient.from("jina", cfg)
   * }}}
   *
   * @param provider the provider name as configured, e.g. the `openai` in
   *                 `EMBEDDING_MODEL=openai/text-embedding-3-small`. Aliases are folded
   *                 onto the canonical id.
   */
  def from(provider: String, cfg: EmbeddingProviderConfig)(using
    ModelRegistryService,
    ProviderRegistry
  ): Result[EmbeddingClient] = {
    val registry = summon[ProviderRegistry]
    val id       = registry.canonicalEmbeddingId(provider)
    registry
      .resolveEmbedding(id, Some("llm4s.embeddings.model"))
      .left
      .map(error =>
        // The registry reports a ConfigurationError; embedding callers have always been
        // handed an EmbeddingError here, and the message carries the registry's diagnostics.
        EmbeddingError(code = Some("400"), message = error.message, provider = "config")
      )
      .flatMap(_.build(cfg))
      .map(new EmbeddingClient(_))
  }

}
