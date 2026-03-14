package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, LocalEmbeddingModels, ModelDimensionRegistry }
import org.llm4s.llmconnect.model.{ Audio, Image, Modality, Text, Video }
import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Selects an embedding model configuration based on input modality.
 *
 * Text modality selection is intentionally disallowed here; callers should
 * load text embedding models through their typed config layer. Image, audio,
 * and video modalities are resolved from the supplied [[LocalEmbeddingModels]] config.
 */
object ModelSelector {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Select a local embedding model by modality.
   *
   * Text modality returns an error since text model selection is configuration-driven.
   * Image, audio, and video modalities resolve against the supplied local-model configuration
   * and look up dimensions from the [[ModelDimensionRegistry]].
   *
   * @param modality the input modality to select a model for
   * @param localModels local model configuration containing model names per modality
   * @return the resolved [[EmbeddingModelConfig]] or an error
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
        ModelDimensionRegistry.getDimension("local", name).map { dim =>
          logger.info(s"[ModelSelector] Image model: $name ($dim dims)")
          EmbeddingModelConfig(name, dim)
        }
      case Audio =>
        val name = localModels.audioModel
        ModelDimensionRegistry.getDimension("local", name).map { dim =>
          logger.info(s"[ModelSelector] Audio model: $name ($dim dims)")
          EmbeddingModelConfig(name, dim)
        }
      case Video =>
        val name = localModels.videoModel
        ModelDimensionRegistry.getDimension("local", name).map { dim =>
          logger.info(s"[ModelSelector] Video model: $name ($dim dims)")
          EmbeddingModelConfig(name, dim)
        }
    }
}
