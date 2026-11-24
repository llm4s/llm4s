package org.llm4s.llmconnect.provider

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse }
import org.llm4s.types.Result

/**
 * Text embedding provider interface for generating vector representations.
 *
 * Provides a unified interface for different embedding services (OpenAI, VoyageAI, Ollama).
 * Each implementation handles provider-specific API calls and response formats.
 *
 * Text is the only input. Media (images, audio, video) is embedded through the separate
 * `MultimodalEmbeddingProvider` capability in `llm4s-rag`, which its `FileEmbedder` calls.
 *
 * == Query or document ==
 *
 * `request.purpose` says whether the texts are documents to index ([[org.llm4s.llmconnect.model.InputPurpose.Document]],
 * the default) or queries to run against them ([[org.llm4s.llmconnect.model.InputPurpose.Query]]). A provider
 * whose API embeds the two differently maps it onto the vendor's own parameter (Voyage and Cohere `input_type`,
 * Jina `task`); a provider whose models embed both alike ignores it.
 *
 * == Usage Example ==
 * {{{
 * val provider: EmbeddingProvider = OpenAIEmbeddingProvider.fromConfig(config)
 * val request = EmbeddingRequest(
 *   input = Seq("Hello world", "How are you?"),
 *   model = EmbeddingModelConfig("text-embedding-3-small", dimensions = 1536)
 * )
 * val result: Result[EmbeddingResponse] = provider.embed(request)
 * }}}
 *
 * @see `OpenAIEmbeddingProvider` for OpenAI text-embedding models (`llm4s-openai`)
 * @see `VoyageAIEmbeddingProvider` for VoyageAI embedding models (`llm4s-voyage`)
 * @see `OllamaEmbeddingProvider` for local Ollama embedding models (`llm4s-ollama`)
 */
@Stable
trait EmbeddingProvider {

  /**
   * Generates embeddings for the given text inputs.
   *
   * @param request embedding request containing input texts and model configuration
   * @return Right(EmbeddingResponse) with vectors on success, Left(error) on failure
   */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse]
}
