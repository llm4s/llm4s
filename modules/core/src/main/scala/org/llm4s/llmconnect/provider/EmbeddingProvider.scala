package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.types.Result

/**
 * Text-only embedding provider interface (e.g., OpenAI, VoyageAI).
 * Multimedia is handled locally via the UniversalEncoder façade.
 */
trait EmbeddingProvider {
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse]
}
