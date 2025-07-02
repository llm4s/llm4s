package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, EmbeddingError }

trait EmbeddingProvider {
  def embed(request: EmbeddingRequest): Either[EmbeddingError, EmbeddingResponse]
}
