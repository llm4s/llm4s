package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingModelConfig, ModelDimensionRegistry }

object ModelSelector {

  def selectModel(): EmbeddingModelConfig = {
    val provider = EmbeddingConfig.activeProvider.toLowerCase

    val modelName = provider match {
      case "openai" => EmbeddingConfig.openAI.model
      case "voyage" => EmbeddingConfig.voyage.model
      case other    => throw new RuntimeException(s"[ModelSelector] Unsupported provider: $other")
    }

    LoggerUtils.info(s"[ModelSelector] Selecting model for provider: $provider, model: $modelName")

    val dimensions = ModelDimensionRegistry.getDimension(provider, modelName)

    LoggerUtils.info(s"[ModelSelector] Model dimensions: $dimensions")

    EmbeddingModelConfig(name = modelName, dimensions = dimensions)
  }
}
