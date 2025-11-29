package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, LocalEmbeddingModels, ModelDimensionRegistry }
import org.llm4s.llmconnect.model.{ Audio, Image, Modality, Text, Video }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

object ModelSelector {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Select a local model by modality.
   * - Text: configuration-driven; callers should select a text model explicitly via their typed config loader.
   * - Image/Audio/Video: use the passed local-model configuration.
   *
   * This keeps ModelSelector config-agnostic: it does not read env/system/HOCON directly.
   */
  def selectModel(modality: Modality, localModels: LocalEmbeddingModels): Result[EmbeddingModelConfig] =
    modality match {
      case Text =>
        Left(
          ConfigurationError(
            "Text model selection is configuration-driven; load a text embedding model via your config layer and pass an EmbeddingModelConfig explicitly."
          )
        )
      case Image =>
        val name = localModels.imageModel
        val dim  = ModelDimensionRegistry.getDimension("local", name)
        logger.info(s"[ModelSelector] Image model: $name ($dim dims)")
        Right(EmbeddingModelConfig(name, dim))
      case Audio =>
        val name = localModels.audioModel
        val dim  = ModelDimensionRegistry.getDimension("local", name)
        logger.info(s"[ModelSelector] Audio model: $name ($dim dims)")
        Right(EmbeddingModelConfig(name, dim))
      case Video =>
        val name = localModels.videoModel
        val dim  = ModelDimensionRegistry.getDimension("local", name)
        logger.info(s"[ModelSelector] Video model: $name ($dim dims)")
        Right(EmbeddingModelConfig(name, dim))
    }
}
