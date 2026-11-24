package org.llm4s.llmconnect.model

import org.llm4s.llmconnect.config.EmbeddingModelConfig

import java.util

/**
 * A request to embed media (images, audio, video), for a
 * [[org.llm4s.llmconnect.provider.MultimodalEmbeddingProvider]]. `FileEmbedder` builds one for each media
 * file it is given.
 *
 * @param inputs   the media to embed, one vector per input, in order
 * @param model    the embedding model and its dimensions
 * @param modality what the inputs are
 * @param meta     caller metadata, such as the file name; empty by default
 */
final case class MultimediaEmbeddingRequest private (
  inputs: Seq[RawMediaInput],
  model: EmbeddingModelConfig,
  modality: Modality,
  meta: Map[String, String]
) {

  /** Sets the inputs. */
  def withInputs(inputs: Seq[RawMediaInput]): MultimediaEmbeddingRequest = copy(inputs = inputs)

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
    inputs: Seq[RawMediaInput],
    model: EmbeddingModelConfig,
    modality: Modality,
    meta: Map[String, String] = Map.empty
  ): MultimediaEmbeddingRequest =
    new MultimediaEmbeddingRequest(inputs, model, modality, meta)
}

/**
 * The bytes of one media object as they are in its file, with their MIME type.
 *
 * Immutable, and equal to another input with the same bytes and MIME type: the bytes are copied in, and
 * read back through an `IArray`.
 */
final class RawMediaInput private (bytes: Array[Byte], val mime: String) {

  /** The media bytes. */
  def data: IArray[Byte] = IArray.unsafeFromArray(bytes)

  /** The number of bytes. */
  def sizeBytes: Long = bytes.length.toLong

  /** Sets the bytes, which are copied. */
  def withData(data: Array[Byte]): RawMediaInput = RawMediaInput(data, mime)

  /** Sets the MIME type. */
  def withMime(mime: String): RawMediaInput = new RawMediaInput(bytes, mime)

  override def equals(other: Any): Boolean = other match {
    case that: RawMediaInput => mime == that.mime && that.sameBytes(bytes)
    case _                   => false
  }

  private def sameBytes(other: Array[Byte]): Boolean = util.Arrays.equals(bytes, other)

  override def hashCode: Int = 31 * util.Arrays.hashCode(bytes) + mime.hashCode

  override def toString: String = s"RawMediaInput($mime, ${bytes.length} bytes)"
}

object RawMediaInput {

  /** Creates a [[RawMediaInput]] holding a copy of `data`, with MIME type `mime`. */
  def apply(data: Array[Byte], mime: String): RawMediaInput = new RawMediaInput(data.clone(), mime)
}
