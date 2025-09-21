package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig

/** Text-only embedding request used by HTTP providers (OpenAI/Voyage). */
final case class EmbeddingRequest(
  input: Seq[String],
  model: EmbeddingModelConfig
)

/**
 * Multimedia request (co-located in the same file to avoid new source files).
 * Used by local encoders/facades (e.g., UniversalEncoder). Not sent to HTTP providers.
 */
final case class MultimediaEmbeddingRequest(
  inputs: Seq[MMInput],
  model: EmbeddingModelConfig,
  modality: Modality,                   // Text | Image | Audio | Video
  meta: Map[String, String] = Map.empty // optional caller/context metadata
)

/** Typed payloads for multimedia inputs. Keep lightweight, encode-friendly. */
sealed trait MMInput {

  /** Approximate payload size in bytes (for logging/back-pressure). */
  def bytesApprox: Long
}

/** Text as pre-chunked strings (optional helper for local text paths). */
final case class TextChunkInput(chunks: Seq[String]) extends MMInput {
  override def bytesApprox: Long = chunks.foldLeft(0L)(_ + _.length)
}

/** Image as raw bytes (e.g., RGB or BGR interleaved) with basic shape metadata. */
final case class ImageInput(
  width: Int,
  height: Int,
  channels: Int,    // usually 3 (RGB) or 1 (grayscale)
  data: Array[Byte] // interleaved by row (row-major)
) extends MMInput {
  override def bytesApprox: Long = data.length.toLong
}

/** Audio as mono float32 PCM with sample rate. */
final case class AudioInput(
  samples: Array[Float], // normalized to [-1, 1]
  sampleRate: Int
) extends MMInput {
  override def bytesApprox: Long = samples.length.toLong * 4L
}

/** Video as a sequence of RGB frames (byte arrays), plus basic shape & fps. */
final case class VideoInput(
  frameWidth: Int,
  frameHeight: Int,
  channels: Int, // typically 3
  fps: Int,
  frames: Seq[Array[Byte]] // each frame: frameWidth*frameHeight*channels bytes
) extends MMInput {
  override def bytesApprox: Long = frames.foldLeft(0L)(_ + _.length)
}
