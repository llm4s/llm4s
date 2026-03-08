package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.{ EmbeddingClient, LLMClient }
import org.llm4s.llmconnect.caching.{ CacheConfig, CachingLLMClient }
import org.llm4s.llmconnect.config.EmbeddingModelConfig
import org.llm4s.trace.Tracing
import java.time.Clock

/**
 * Middleware adapter for CachingLLMClient.
 *
 * Allows using semantic caching as part of the middleware pipeline.
 */
class CachingMiddleware(
  embeddingClient: EmbeddingClient,
  embeddingModel: EmbeddingModelConfig,
  config: CacheConfig,
  tracing: Tracing,
  clock: Clock = Clock.systemUTC()
) extends LLMMiddleware {

  override def name: String = "caching"

  override def wrap(next: LLMClient): LLMClient =
    new CachingLLMClient(
      baseClient = next,
      embeddingClient = embeddingClient,
      embeddingModel = embeddingModel,
      config = config,
      tracing = tracing,
      clock = clock
    )
}
