package org.llm4s.llmconnect.model

import org.llm4s.error.LLMError

/**
 * EmbeddingError represents a structured error returned from
 * an embedding provider (e.g., OpenAI or VoyageAI) or local encoders/extractors.
 *
 * @param code Optional error code, typically an HTTP status (e.g., "401", "400").
 * @param message Human-readable error message from the provider or client.
 * @param provider Source component ("openai", "voyage", "encoder", "extractor", etc.)
 */
final case class EmbeddingError(
  override val code: Option[String],
  override val message: String,
  provider: String
) extends LLMError {
  override val context: Map[String, String] = Map("provider" -> provider)
}
