package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.types.Result

/**
 * Text embedding provider interface for generating vector representations.
 *
 * Provides a unified interface for different embedding services (OpenAI, VoyageAI, Ollama).
 * Each implementation handles provider-specific API calls and response formats.
 *
 * Text content is the primary input; multimedia content (images, audio) should be
 * processed through the UniversalEncoder façade which handles content extraction
 * before embedding.
 *
 * == Usage Example ==
 * {{{
 * val provider: EmbeddingProvider = OpenAIEmbeddingProvider.fromConfig(config)
 * val request = EmbeddingRequest(
 *   input = Seq("Hello world", "How are you?"),
 *   model = EmbeddingModelName("text-embedding-3-small")
 * )
 * val result: Result[EmbeddingResponse] = provider.embed(request)
 * }}}
 *
 * @see [[OpenAIEmbeddingProvider]] for OpenAI text-embedding models
 * @see [[VoyageAIEmbeddingProvider]] for VoyageAI embedding models
 * @see [[OllamaEmbeddingProvider]] for local Ollama embedding models
 */
trait EmbeddingProvider {

  /**
   * Generates embeddings for the given text inputs.
   *
   * @param request embedding request containing input texts and model configuration
   * @return Right(EmbeddingResponse) with vectors on success, Left(error) on failure
   */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse]

  /**
   * Generates embeddings for multimedia inputs.
   * By default, returns a 501 Not Implemented error for providers that do not
   * support genuine multimodal embedding endpoints.
   *
   * @param request multimodal embedding request
   * @return Right(EmbeddingResponse) with vectors on success, Left(error) on failure
   */
  def embedMultimodal(request: org.llm4s.llmconnect.model.MultimediaEmbeddingRequest): Result[EmbeddingResponse] =
    Left(
      org.llm4s.llmconnect.model.EmbeddingError(
        code = Some("501"),
        message = s"Multimodal embeddings (modality: ${request.modality}) not implemented by this provider.",
        provider = "encoder"
      )
    )
}
