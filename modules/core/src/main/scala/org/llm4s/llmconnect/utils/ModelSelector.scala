package org.llm4s.llmconnect.utils

import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ModelDimensionRegistry }
import org.llm4s.llmconnect.model.{ Audio, Image, Modality, Text, Video }
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

object ModelSelector {

  private val logger = LoggerFactory.getLogger(getClass)

  // Local stub model names for non-text modalities. Dimensions are defined in ModelDimensionRegistry.
  private val LocalImageModel = "openclip-vit-b32"
  private val LocalAudioModel = "wav2vec2-base"
  private val LocalVideoModel = "timesformer-base"

  /**
   * Select a local model by modality.
   * - Text: uses a simple default local model name for stubs.
   * - Image/Audio/Video: use static \"local\" models known to ModelDimensionRegistry.
   *
   * Note: text model selection from real configuration is handled by Llm4sConfig in
   * application code; this selector is only responsible for local/stub models.
   */
  def selectModel(modality: Modality): Result[EmbeddingModelConfig] = modality match {
    case Text =>
      // For text, real model selection is handled via Llm4sConfig in application code.
      // ModelSelector only exposes local/stub models for non-text modalities.
      Right(EmbeddingModelConfig("local-text-stub", ModelDimensionRegistry.getDimension("local", "local-text-stub")))
    case Image =>
      val name = LocalImageModel
      val dim  = ModelDimensionRegistry.getDimension("local", name)
      logger.info(s"[ModelSelector] Image model: $name ($dim dims)")
      Right(EmbeddingModelConfig(name, dim))
    case Audio =>
      val name = LocalAudioModel
      val dim  = ModelDimensionRegistry.getDimension("local", name)
      logger.info(s"[ModelSelector] Audio model: $name ($dim dims)")
      Right(EmbeddingModelConfig(name, dim))
    case Video =>
      val name = LocalVideoModel
      val dim  = ModelDimensionRegistry.getDimension("local", name)
      logger.info(s"[ModelSelector] Video model: $name ($dim dims)")
      Right(EmbeddingModelConfig(name, dim))
  }
}
