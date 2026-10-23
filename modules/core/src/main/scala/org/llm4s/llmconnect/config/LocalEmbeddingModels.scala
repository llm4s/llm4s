package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable

/**
 * Configuration specifying local model names for non-text modality embedding.
 *
 * Holds the model identifiers used by local encoders to produce embeddings for
 * image, audio, and video content. These models run locally (e.g. via ONNX or
 * stub implementations) rather than calling a remote API.
 *
 * @param imageModel Local model name for image embeddings (e.g. "openclip-vit-b32").
 * @param audioModel Local model name for audio embeddings (e.g. "wav2vec2-base").
 * @param videoModel Local model name for video embeddings (e.g. "timesformer-base").
 *
 * Build one with [[LocalEmbeddingModels.apply]] and change it with the `with*` setters; the constructor and `copy`
 * are private, so a new modality never breaks callers.
 */
@Stable
final case class LocalEmbeddingModels private (
  imageModel: String,
  audioModel: String,
  videoModel: String
) {

  /** Sets the local model name for image embeddings. */
  def withImageModel(imageModel: String): LocalEmbeddingModels = copy(imageModel = imageModel)

  /** Sets the local model name for audio embeddings. */
  def withAudioModel(audioModel: String): LocalEmbeddingModels = copy(audioModel = audioModel)

  /** Sets the local model name for video embeddings. */
  def withVideoModel(videoModel: String): LocalEmbeddingModels = copy(videoModel = videoModel)
}

object LocalEmbeddingModels {

  /** Creates a [[LocalEmbeddingModels]] from the image, audio and video model names. */
  def apply(imageModel: String, audioModel: String, videoModel: String): LocalEmbeddingModels =
    new LocalEmbeddingModels(imageModel, audioModel, videoModel)
}
