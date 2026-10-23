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
 * Providers can set `modality`/`dim`/`usage` when they know it; callers can ignore safely.
 *
 * Build one with [[EmbeddingResponse.apply]] and change it with the `with*` setters (an `Option` field's setter
 * takes the value or an `Option`); the constructor and `copy` are private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingResponse private (
  embeddings: Seq[Seq[Double]],
  metadata: Map[String, String],
  modality: Option[Modality],
  dim: Option[Int],
  usage: Option[EmbeddingUsage]
) {

  /** Sets the embedding vectors, one per input. */
  def withEmbeddings(embeddings: Seq[Seq[Double]]): EmbeddingResponse = copy(embeddings = embeddings)

  /** Sets the provider and model metadata. */
  def withMetadata(metadata: Map[String, String]): EmbeddingResponse = copy(metadata = metadata)

  /** Sets the overall modality. */
  def withModality(modality: Modality): EmbeddingResponse = copy(modality = Some(modality))

  /** Sets, or with `None` clears, the overall modality. */
  def withModality(modality: Option[Modality]): EmbeddingResponse = copy(modality = modality)

  /** Sets the vector dimensionality. */
  def withDim(dim: Int): EmbeddingResponse = copy(dim = Some(dim))

  /** Sets, or with `None` clears, the vector dimensionality. */
  def withDim(dim: Option[Int]): EmbeddingResponse = copy(dim = dim)

  /** Sets the token usage. */
  def withUsage(usage: EmbeddingUsage): EmbeddingResponse = copy(usage = Some(usage))

  /** Sets, or with `None` clears, the token usage. */
  def withUsage(usage: Option[EmbeddingUsage]): EmbeddingResponse = copy(usage = usage)
}

object EmbeddingResponse {

  /**
   * Creates an [[EmbeddingResponse]].
   *
   * @param embeddings one vector per input
   * @param metadata   provider and model metadata
   * @param modality   overall modality, when known
   * @param dim        vector dimensionality, when known
   * @param usage      token usage, when the provider reports it
   */
  def apply(
    embeddings: Seq[Seq[Double]],
    metadata: Map[String, String] = Map.empty,
    modality: Option[Modality] = None,
    dim: Option[Int] = None,
    usage: Option[EmbeddingUsage] = None
  ): EmbeddingResponse =
    new EmbeddingResponse(embeddings, metadata, modality, dim, usage)

  /** Convenience factory for an empty response. */
  val empty: EmbeddingResponse = EmbeddingResponse(Nil)
}
