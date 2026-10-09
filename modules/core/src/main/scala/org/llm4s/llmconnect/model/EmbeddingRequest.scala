package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.config.EmbeddingModelConfig

/**
 * Text-only embedding request used by HTTP providers (OpenAI/Voyage).
 *
 * @param input   the texts to embed, one vector per text, in order
 * @param model   the embedding model and its dimensions
 * @param purpose whether the texts are documents to index or queries to run against them; defaults
 *                to [[InputPurpose.Document]]. Providers whose models embed the two differently
 *                (Voyage, Jina, Cohere) map it onto their own parameter; the rest ignore it.
 */
@Stable
final case class EmbeddingRequest private (
  input: Seq[String],
  model: EmbeddingModelConfig,
  purpose: InputPurpose
) {
  def withInput(input: Seq[String]): EmbeddingRequest          = copy(input = input)
  def withModel(model: EmbeddingModelConfig): EmbeddingRequest = copy(model = model)
  def withPurpose(purpose: InputPurpose): EmbeddingRequest     = copy(purpose = purpose)
}

object EmbeddingRequest:
  /**
   * Creates an [[EmbeddingRequest]]. Named arguments are the supported way to construct one;
   * `purpose` defaults to [[InputPurpose.Document]].
   */
  def apply(
    input: Seq[String],
    model: EmbeddingModelConfig,
    purpose: InputPurpose = InputPurpose.Document
  ): EmbeddingRequest =
    new EmbeddingRequest(input, model, purpose)

/**
 * Multimedia request (co-located in the same file to avoid new source files).
 * Used by local encoders/facades (e.g., `FileEmbedder` in `llm4s-rag`). Not sent to HTTP providers.
 */
@Stable
final case class MultimediaEmbeddingRequest(
  inputs: Seq[MMInput],
  model: EmbeddingModelConfig,
  modality: Modality,                   // Text | Image | Audio | Video
  meta: Map[String, String] = Map.empty // optional caller/context metadata
)

/** Typed payloads for multimedia inputs. Keep lightweight, encode-friendly. */
@Stable
sealed trait MMInput {

  /** Approximate payload size in bytes (for logging/back-pressure). */
  def bytesApprox: Long
}

/** Text as pre-chunked strings (optional helper for local text paths). */
@Stable
final case class TextChunkInput(chunks: Seq[String]) extends MMInput {
  override def bytesApprox: Long = chunks.foldLeft(0L)(_ + _.length)
}

/** Image as raw bytes (e.g., RGB or BGR interleaved) with basic shape metadata. */
@Stable
final case class ImageInput(
  width: Int,
  height: Int,
  channels: Int,    // usually 3 (RGB) or 1 (grayscale)
  data: Array[Byte] // interleaved by row (row-major)
) extends MMInput {
  override def bytesApprox: Long = data.length.toLong
}

/** Audio as mono float32 PCM with sample rate. */
@Stable
final case class AudioInput(
  samples: Array[Float], // normalized to [-1, 1]
  sampleRate: Int
) extends MMInput {
  override def bytesApprox: Long = samples.length.toLong * 4L
}

/** Video as a sequence of RGB frames (byte arrays), plus basic shape & fps. */
@Stable
final case class VideoInput(
  frameWidth: Int,
  frameHeight: Int,
  channels: Int, // typically 3
  fps: Int,
  frames: Seq[Array[Byte]] // each frame: frameWidth*frameHeight*channels bytes
) extends MMInput {
  override def bytesApprox: Long = frames.foldLeft(0L)(_ + _.length)
}

/** Raw, unparsed bytes representing the media object. Provided for generic multimodal HTTP providers. */
@Stable
final case class RawMediaInput(
  data: Array[Byte],
  mime: String
) extends MMInput {
  override def bytesApprox: Long = data.length.toLong
}
