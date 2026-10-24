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
final case class MultimediaEmbeddingRequest private (
  inputs: Seq[MMInput],
  model: EmbeddingModelConfig,
  modality: Modality,       // Text | Image | Audio | Video
  meta: Map[String, String] // optional caller/context metadata
) {

  /** Sets the inputs. */
  def withInputs(inputs: Seq[MMInput]): MultimediaEmbeddingRequest = copy(inputs = inputs)

  /** Sets the embedding model. */
  def withModel(model: EmbeddingModelConfig): MultimediaEmbeddingRequest = copy(model = model)

  /** Sets the modality. */
  def withModality(modality: Modality): MultimediaEmbeddingRequest = copy(modality = modality)

  /** Sets the caller metadata. */
  def withMeta(meta: Map[String, String]): MultimediaEmbeddingRequest = copy(meta = meta)
}

object MultimediaEmbeddingRequest {

  /** Creates a [[MultimediaEmbeddingRequest]]; `meta` is empty by default. */
  def apply(
    inputs: Seq[MMInput],
    model: EmbeddingModelConfig,
    modality: Modality,
    meta: Map[String, String] = Map.empty
  ): MultimediaEmbeddingRequest =
    new MultimediaEmbeddingRequest(inputs, model, modality, meta)
}

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
final case class ImageInput private (
  width: Int,
  height: Int,
  channels: Int,    // usually 3 (RGB) or 1 (grayscale)
  data: Array[Byte] // interleaved by row (row-major)
) extends MMInput {
  override def bytesApprox: Long = data.length.toLong

  /** Sets the width in pixels. */
  def withWidth(width: Int): ImageInput = copy(width = width)

  /** Sets the height in pixels. */
  def withHeight(height: Int): ImageInput = copy(height = height)

  /** Sets the number of channels. */
  def withChannels(channels: Int): ImageInput = copy(channels = channels)

  /** Sets the pixel bytes. */
  def withData(data: Array[Byte]): ImageInput = copy(data = data)
}

object ImageInput {

  /** Creates an [[ImageInput]] of `width` x `height` pixels with `channels` channels, row-major. */
  def apply(width: Int, height: Int, channels: Int, data: Array[Byte]): ImageInput =
    new ImageInput(width, height, channels, data)
}

/** Audio as mono float32 PCM with sample rate. */
@Stable
final case class AudioInput private (
  samples: Array[Float], // normalized to [-1, 1]
  sampleRate: Int
) extends MMInput {
  override def bytesApprox: Long = samples.length.toLong * 4L

  /** Sets the samples, normalized to [-1, 1]. */
  def withSamples(samples: Array[Float]): AudioInput = copy(samples = samples)

  /** Sets the sample rate in Hz. */
  def withSampleRate(sampleRate: Int): AudioInput = copy(sampleRate = sampleRate)
}

object AudioInput {

  /** Creates an [[AudioInput]] of mono float32 `samples` at `sampleRate` Hz. */
  def apply(samples: Array[Float], sampleRate: Int): AudioInput = new AudioInput(samples, sampleRate)
}

/** Video as a sequence of RGB frames (byte arrays), plus basic shape & fps. */
@Stable
final case class VideoInput private (
  frameWidth: Int,
  frameHeight: Int,
  channels: Int, // typically 3
  fps: Int,
  frames: Seq[Array[Byte]] // each frame: frameWidth*frameHeight*channels bytes
) extends MMInput {
  override def bytesApprox: Long = frames.foldLeft(0L)(_ + _.length)

  /** Sets the frame width in pixels. */
  def withFrameWidth(frameWidth: Int): VideoInput = copy(frameWidth = frameWidth)

  /** Sets the frame height in pixels. */
  def withFrameHeight(frameHeight: Int): VideoInput = copy(frameHeight = frameHeight)

  /** Sets the number of channels. */
  def withChannels(channels: Int): VideoInput = copy(channels = channels)

  /** Sets the frame rate. */
  def withFps(fps: Int): VideoInput = copy(fps = fps)

  /** Sets the frames. */
  def withFrames(frames: Seq[Array[Byte]]): VideoInput = copy(frames = frames)
}

object VideoInput {

  /** Creates a [[VideoInput]] of `frames`, each `frameWidth` x `frameHeight` x `channels` bytes, at `fps`. */
  def apply(frameWidth: Int, frameHeight: Int, channels: Int, fps: Int, frames: Seq[Array[Byte]]): VideoInput =
    new VideoInput(frameWidth, frameHeight, channels, fps, frames)
}

/** Raw, unparsed bytes representing the media object. Provided for generic multimodal HTTP providers. */
@Stable
final case class RawMediaInput private (
  data: Array[Byte],
  mime: String
) extends MMInput {
  override def bytesApprox: Long = data.length.toLong

  /** Sets the bytes. */
  def withData(data: Array[Byte]): RawMediaInput = copy(data = data)

  /** Sets the MIME type. */
  def withMime(mime: String): RawMediaInput = copy(mime = mime)
}

object RawMediaInput {

  /** Creates a [[RawMediaInput]] of `data` with MIME type `mime`. */
  def apply(data: Array[Byte], mime: String): RawMediaInput = new RawMediaInput(data, mime)
}
