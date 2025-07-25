package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry }

/**
 * Selects the appropriate embedding model configuration based on the provider
 * and input text characteristics (e.g., size).
 */
object ModelSelector {

  /**
   * Chooses a model configuration dynamically based on provider and text content.
   *
   * @param provider The embedding provider, e.g., "openai", "voyage"
   * @param text The raw input text to be embedded
   * @return An EmbeddingModelConfig containing the chosen model name and vector dimensions
   */
  def selectModel(provider: String, text: String): EmbeddingModelConfig = {
    val tokenCount = estimateTokenCount(text)

    provider.toLowerCase match {
      case "openai" =>
        val model = if (tokenCount <= 1000) "text-embedding-3-small" else "text-embedding-3-large"
        val dims  = ModelDimensionRegistry.getDimensions("openai", model)
        EmbeddingModelConfig(model, dims)

      case "voyage" =>
        val model = if (tokenCount <= 1000) "voyage-3.5" else "voyage-code-2"
        val dims  = ModelDimensionRegistry.getDimensions("voyage", model)
        EmbeddingModelConfig(model, dims)

      case other =>
        throw new RuntimeException(s"Unsupported provider: $other")
    }
  }

  /**
   * Estimate token count using a naive approximation (split by whitespace).
   * In production, use tokenizer-aware estimation (e.g., tiktoken or sentencepiece).
   */
  def estimateTokenCount(text: String): Int =
    text.split("\\s+").length
}
