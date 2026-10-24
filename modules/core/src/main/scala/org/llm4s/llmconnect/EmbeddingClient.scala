package org.llm4s.llmconnect

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.config.EmbeddingProviderConfig
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.llmconnect.spi.ProviderRegistry
import org.llm4s.model.ModelRegistryService
import org.llm4s.trace.{ TraceAttributeNames, TraceEvent, Tracing }
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.concurrent.duration.{ FiniteDuration, NANOSECONDS }

/**
 * Embeds text through an [[org.llm4s.llmconnect.provider.EmbeddingProvider]]. With a tracer, each call is traced: a completed call as one
 * [[org.llm4s.trace.TraceEvent.EmbeddingUsageRecorded]] (with the call's provider and duration) and, when its model's
 * pricing is known, a cost; a failed call as one [[org.llm4s.trace.TraceEvent.ErrorOccurred]] naming the `embeddings`
 * operation (#2275).
 *
 * @param providerId the canonical id of the provider, which tracing reports; [[EmbeddingClient.from]] sets it
 */
@Stable
class EmbeddingClient(
  provider: EmbeddingProvider,
  tracer: Option[Tracing] = None,
  operation: String = "embedding",
  val providerId: Option[ProviderId] = None
)(using service: ModelRegistryService) {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Text embeddings via the configured HTTP provider. */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    logger.debug(s"[EmbeddingClient] Embedding with model=${request.model.name}, inputs=${request.input.size}")
    val startedAt = System.nanoTime()
    val result    = provider.embed(request)
    traced(result, request.model.name, request.input.size, startedAt)
  }

  /** Multimodal embeddings via the configured HTTP provider. */
  def embedMultimodal(
    request: org.llm4s.llmconnect.model.MultimediaEmbeddingRequest
  ): Result[EmbeddingResponse] = {
    logger.debug(
      s"[EmbeddingClient] Multimodal embedding with model=${request.model.name}, modality=${request.modality}"
    )
    val startedAt = System.nanoTime()
    val result    = provider.embedMultimodal(request)
    traced(result, request.model.name, request.inputs.size, startedAt)
  }

  // Emits the call's trace events when tracing is enabled: its usage and cost, or its failure
  private def traced(
    result: Result[EmbeddingResponse],
    model: String,
    inputCount: Int,
    startedAt: Long
  ): Result[EmbeddingResponse] = {
    val duration = Some(FiniteDuration(math.max(0L, System.nanoTime() - startedAt), NANOSECONDS))
    tracer.foreach { t =>
      result match {
        case Right(response) =>
          response.usage.foreach { usage =>
            t.traceEvent(
              TraceEvent.EmbeddingUsageRecorded(
                usage = usage,
                model = model,
                operation = operation,
                inputCount = inputCount,
                provider = providerId,
                duration = duration
              )
            )

            // Try to calculate and emit cost
            service.lookup(model).foreach { meta =>
              meta.pricing.estimateCost(usage.promptTokens, 0).foreach { cost =>
                t.traceCost(
                  costUsd = cost,
                  model = model,
                  operation = operation,
                  tokenCount = usage.totalTokens,
                  costType = "embedding"
                )
              }
            }
          }
        case Left(error) =>
          t.traceEvent(
            TraceEvent.ErrorOccurred(
              new RuntimeException(error.message),
              TraceAttributeNames.Embeddings,
              operation = Some(TraceAttributeNames.Embeddings),
              model = Some(model),
              provider = providerId,
              errorType = Some(error.getClass.getSimpleName),
              duration = duration
            )
          )
      }
    }
    result
  }

  /** Create a new client with tracing enabled. */
  def withTracing(tracer: Tracing): EmbeddingClient =
    new EmbeddingClient(provider, Some(tracer), operation, providerId)

  /** Create a new client with a specific operation label for tracing. */
  def withOperation(op: String): EmbeddingClient =
    new EmbeddingClient(provider, tracer, op, providerId)
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
      .map(new EmbeddingClient(_, providerId = Some(id)))
  }

}
