package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

/**
 * Successful response carrying embedding vectors and lightweight metadata.
 *
 * @param embeddings  One vector per input text/chunk (or per item).
 * @param metadata    Provider/model info etc. (e.g., "provider" -> "openai", "model" -> "...").
 * @param modality    Optional overall modality tag (Text, Audio, Video) when known.
 * @param dim         Optional dimensionality, if convenient to surface at response-level.
 * @param usage       Optional token usage statistics (available from providers like OpenAI).
 *
 * Notes:
 * - Defaults on `metadata`, `modality`, `dim`, and `usage` keep old call-sites source-compatible.
 * - Providers can set `modality`/`dim`/`usage` when they know it; callers can ignore safely.
 */
@Stable
final case class EmbeddingResponse(
  embeddings: Seq[Seq[Double]],
  metadata: Map[String, String] = Map.empty,
  modality: Option[Modality] = None,
  dim: Option[Int] = None,
  usage: Option[EmbeddingUsage] = None
)

object EmbeddingResponse {

  /** Convenience factory for an empty response. */
  val empty: EmbeddingResponse = EmbeddingResponse(Nil)
}
