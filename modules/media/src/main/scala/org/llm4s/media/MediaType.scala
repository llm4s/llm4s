package org.llm4s.media

/**
 * A media type: its MIME string, its canonical file extension, and its [[MediaCategory]].
 *
 * This is the shared vocabulary the multimodal modules speak. Before it existed, `llm4s-core`
 * carried three overlapping enumerations of the same handful of image formats -
 * `imagegeneration.ImageFormat`, `imageprocessing.ImageFormat` and `imageprocessing.MediaType` -
 * which could not be passed to one another despite being structurally identical, and RAG's
 * media extraction matched on raw MIME prefixes instead.
 *
 * `llm4s-media` holds the vocabulary only: no I/O, no sniffing, no third-party dependencies.
 * Deciding what a file actually *is* from its bytes needs Tika, which lives in `llm4s-rag`;
 * that code produces a MIME string and resolves it here via [[MediaType.fromMimeType]].
 */
sealed trait MediaType extends Product with Serializable {

  /** The MIME type, e.g. `"image/png"`. */
  def mimeType: String

  /** The canonical file extension, without a leading dot, e.g. `"png"`. */
  def extension: String

  /** The broad kind of media this is. */
  def category: MediaCategory
}

/** A [[MediaType]] statically known to be an image, so image APIs can require one. */
sealed trait ImageMediaType extends MediaType {
  final def category: MediaCategory = MediaCategory.Image
}

/** A [[MediaType]] statically known to be audio, so audio APIs can require one. */
sealed trait AudioMediaType extends MediaType {
  final def category: MediaCategory = MediaCategory.Audio
}

object MediaType {

  case object Png extends ImageMediaType {
    val mimeType  = "image/png"
    val extension = "png"
  }

  case object Jpeg extends ImageMediaType {
    val mimeType  = "image/jpeg"
    val extension = "jpg"
  }

  case object Gif extends ImageMediaType {
    val mimeType  = "image/gif"
    val extension = "gif"
  }

  case object WebP extends ImageMediaType {
    val mimeType  = "image/webp"
    val extension = "webp"
  }

  case object Bmp extends ImageMediaType {
    val mimeType  = "image/bmp"
    val extension = "bmp"
  }

  case object Tiff extends ImageMediaType {
    val mimeType  = "image/tiff"
    val extension = "tiff"
  }

  case object Wav extends AudioMediaType {
    val mimeType  = "audio/wav"
    val extension = "wav"
  }

  case object Mp3 extends AudioMediaType {
    val mimeType  = "audio/mpeg"
    val extension = "mp3"
  }

  case object Flac extends AudioMediaType {
    val mimeType  = "audio/flac"
    val extension = "flac"
  }

  case object Ogg extends AudioMediaType {
    val mimeType  = "audio/ogg"
    val extension = "ogg"
  }

  /** Every known image type, in declaration order. */
  val images: Seq[ImageMediaType] = Seq(Png, Jpeg, Gif, WebP, Bmp, Tiff)

  /** Every known audio type, in declaration order. */
  val audio: Seq[AudioMediaType] = Seq(Wav, Mp3, Flac, Ogg)

  /** Every known media type. */
  val all: Seq[MediaType] = images ++ audio

  /**
   * Extensions that name a type but are not its canonical [[MediaType.extension]].
   *
   * Kept beside the types themselves so `.jpeg` and `.tif` resolve wherever a path is parsed,
   * rather than in whichever call site happened to remember them.
   */
  private val extensionAliases: Map[String, MediaType] =
    Map("jpeg" -> Jpeg, "tif" -> Tiff, "wave" -> Wav, "oga" -> Ogg)

  private val byExtension: Map[String, MediaType] =
    all.map(t => t.extension -> t).toMap ++ extensionAliases

  private val byMimeType: Map[String, MediaType] =
    all.map(t => t.mimeType -> t).toMap ++ Map(
      // Registered alternates that appear in the wild for types we already model.
      "image/jpg"    -> Jpeg,
      "audio/x-wav"  -> Wav,
      "audio/mp3"    -> Mp3,
      "audio/x-flac" -> Flac
    )

  /** The media type named by a file extension, with or without a leading dot. */
  def fromExtension(extension: String): Option[MediaType] =
    byExtension.get(extension.trim.toLowerCase.stripPrefix("."))

  /** The media type named by the extension of a file path or URL. */
  def fromPath(path: String): Option[MediaType] = {
    val trimmed = path.trim
    val name    = trimmed.substring(trimmed.lastIndexOf('/') + 1)
    if (name.contains('.')) fromExtension(name.substring(name.lastIndexOf('.') + 1)) else None
  }

  /** The media type with this MIME string, ignoring any `;charset=...` parameters. */
  def fromMimeType(mimeType: String): Option[MediaType] =
    byMimeType.get(mimeType.trim.toLowerCase.takeWhile(_ != ';').trim)

  /** As [[fromExtension]], but only matches image types. */
  def imageFromExtension(extension: String): Option[ImageMediaType] =
    fromExtension(extension).collect { case i: ImageMediaType => i }

  /** As [[fromPath]], but only matches image types. */
  def imageFromPath(path: String): Option[ImageMediaType] =
    fromPath(path).collect { case i: ImageMediaType => i }

  /** As [[fromMimeType]], but only matches image types. */
  def imageFromMimeType(mimeType: String): Option[ImageMediaType] =
    fromMimeType(mimeType).collect { case i: ImageMediaType => i }
}
