package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingModelConfig, ModelDimensionRegistry }
import org.slf4j.LoggerFactory

/** TEXT-ONLY model selection. */
object ModelSelector {
  private val logger = LoggerFactory.getLogger(getClass)

  def selectTextModel(): EmbeddingModelConfig = {
    val provider = EmbeddingConfig.activeProvider.toLowerCase
    val model = (provider match {
      case "openai" => EmbeddingConfig.openAI.textModel
      case "voyage" => EmbeddingConfig.voyage.textModel
      case other    => throw new RuntimeException(s"[ModelSelector] Unsupported provider: $other")
    }).getOrElse("<unset>")

    val dims = ModelDimensionRegistry.getDimension(provider, model, EmbeddingConfig.textDims)
    logger.info(s"[ModelSelector] Text: provider=$provider model=$model dims=$dims")
    EmbeddingModelConfig(model, dims)
  }
}
