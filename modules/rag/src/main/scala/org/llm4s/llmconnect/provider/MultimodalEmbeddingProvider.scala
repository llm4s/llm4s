package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ EmbeddingResponse, MultimediaEmbeddingRequest }
import org.llm4s.types.Result

/**
 * Embeds media: images, audio, video. A capability of its own, apart from
 * [[org.llm4s.llmconnect.provider.EmbeddingProvider]], because most embedding providers are text only; no
 * provider llm4s ships implements it.
 *
 * Implement it over a multimodal embedding endpoint and pass it to `FileEmbedder.encodeFromPath`, which
 * calls it for the media files it is given.
 */
trait MultimodalEmbeddingProvider {

  /**
   * Generates one embedding per input of the request.
   *
   * @param request the media to embed, their modality and the model
   * @return Right(EmbeddingResponse) with vectors on success, Left(error) on failure
   */
  def embedMultimodal(request: MultimediaEmbeddingRequest): Result[EmbeddingResponse]
}
