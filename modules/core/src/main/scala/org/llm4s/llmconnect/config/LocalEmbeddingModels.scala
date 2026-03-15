package org.llm4s.llmconnect.config

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
 */
final case class LocalEmbeddingModels(
  imageModel: String,
  audioModel: String,
  videoModel: String
)
