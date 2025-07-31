package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingModelConfig, ModelDimensionRegistry }
import org.slf4j.LoggerFactory

object ModelSelector {

  private val logger = LoggerFactory.getLogger(getClass)

  def selectModel(): EmbeddingModelConfig = {
    val provider = EmbeddingConfig.activeProvider.toLowerCase

    val modelName = provider match {
      case "openai" => EmbeddingConfig.openAI.model
      case "voyage" => EmbeddingConfig.voyage.model
      case other =>
        throw new RuntimeException(s"[ModelSelector] Unsupported provider: $other")
    }

    logger.info(s"\n[ModelSelector] Selecting model for provider: $provider, model: $modelName")

    val dimensions = ModelDimensionRegistry.getDimension(provider, modelName)

    logger.info(s"\n[ModelSelector] Model dimensions: $dimensions")

    EmbeddingModelConfig(name = modelName, dimensions = dimensions)
  }
}
