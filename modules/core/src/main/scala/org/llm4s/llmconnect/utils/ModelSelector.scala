package org.llm4s.llmconnect.utils

import org.llm4s.config.ConfigReader
import org.llm4s.llmconnect.config.{ EmbeddingConfig, EmbeddingModelConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.model.{ Modality, Text, Image, Audio, Video }
import org.slf4j.LoggerFactory

object ModelSelector {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Legacy selector used by text HTTP providers (OpenAI/Voyage).
   * Chooses model based on EMBEDDING_PROVIDER and provider-specific envs.
   */
  def selectModel(config: ConfigReader): EmbeddingModelConfig = {
    val provider = EmbeddingConfig.activeProvider(config).toLowerCase

    val modelName = provider match {
      case "openai" => EmbeddingConfig.openAI(config).model
      case "voyage" => EmbeddingConfig.voyage(config).model
      case other =>
        throw new RuntimeException(s"[ModelSelector] Unsupported provider: $other")
    }

    logger.info(s"[ModelSelector] Selecting model for provider: $provider, model: $modelName")

    val dimensions = ModelDimensionRegistry.getDimension(provider, modelName)
    logger.info(s"[ModelSelector] Model dimensions: $dimensions")

    EmbeddingModelConfig(name = modelName, dimensions = dimensions)
  }

  /**
   * New overload that selects by modality.
   * - Text uses the legacy provider-based selection.
   * - Image/Audio/Video use static "local" models defined in EmbeddingConfig.
   */
  def selectModel(modality: Modality, config: ConfigReader): EmbeddingModelConfig = modality match {
    case Text =>
      selectModel(config) // defer to provider-based text selection
    case Image =>
      val name = EmbeddingConfig.imageModel(config)
      val dim  = ModelDimensionRegistry.getDimension("local", name)
      logger.info(s"[ModelSelector] Image model: $name ($dim dims)")
      EmbeddingModelConfig(name, dim)
    case Audio =>
      val name = EmbeddingConfig.audioModel(config)
      val dim  = ModelDimensionRegistry.getDimension("local", name)
      logger.info(s"[ModelSelector] Audio model: $name ($dim dims)")
      EmbeddingModelConfig(name, dim)
    case Video =>
      val name = EmbeddingConfig.videoModel(config)
      val dim  = ModelDimensionRegistry.getDimension("local", name)
      logger.info(s"[ModelSelector] Video model: $name ($dim dims)")
      EmbeddingModelConfig(name, dim)
  }
}
