package org.llm4s.llmconnect.utils

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingModelConfig, ModelDimensionRegistry }
import org.slf4j.LoggerFactory

object ModelSelector {

  private val logger = LoggerFactory.getLogger(getClass)

  def selectModel(config: ConfigReader): EmbeddingModelConfig = {
    val provider = EmbeddingConfig.activeProvider(config).toLowerCase

    val modelName = provider match {
      case "openai" => EmbeddingConfig.openAI(config).model
      case "voyage" => EmbeddingConfig.voyage(config).model
      case other =>
        throw new RuntimeException(s"[ModelSelector] Unsupported provider: $other")
    }

    logger.info(s"\n[ModelSelector] Selecting model for provider: $provider, model: $modelName")

    val dimensions = ModelDimensionRegistry.getDimension(provider, modelName)

    logger.info(s"\n[ModelSelector] Model dimensions: $dimensions")

    EmbeddingModelConfig(name = modelName, dimensions = dimensions)
  }
}
