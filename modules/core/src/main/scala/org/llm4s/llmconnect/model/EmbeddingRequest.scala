package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.config.EmbeddingModelConfig

/**
 * Text embedding request, as every [[org.llm4s.llmconnect.provider.EmbeddingProvider]] takes it.
 *
 * @param input   the texts to embed, one vector per text, in order
 * @param model   the embedding model and its dimensions
 * @param purpose whether the texts are documents to index or queries to run against them; defaults
 *                to [[InputPurpose.Document]]. Providers whose models embed the two differently
 *                (Voyage, Jina, Cohere) map it onto their own parameter; the rest ignore it.
 */
@Stable
final case class EmbeddingRequest private (
  input: Seq[String],
  model: EmbeddingModelConfig,
  purpose: InputPurpose
) {
  def withInput(input: Seq[String]): EmbeddingRequest          = copy(input = input)
  def withModel(model: EmbeddingModelConfig): EmbeddingRequest = copy(model = model)
  def withPurpose(purpose: InputPurpose): EmbeddingRequest     = copy(purpose = purpose)
}

object EmbeddingRequest:
  /**
   * Creates an [[EmbeddingRequest]]. Named arguments are the supported way to construct one;
   * `purpose` defaults to [[InputPurpose.Document]].
   */
  def apply(
    input: Seq[String],
    model: EmbeddingModelConfig,
    purpose: InputPurpose = InputPurpose.Document
  ): EmbeddingRequest =
    new EmbeddingRequest(input, model, purpose)
