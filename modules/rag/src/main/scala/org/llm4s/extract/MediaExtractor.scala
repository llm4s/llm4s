package org.llm4s.extract

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import scala.util.{ Failure, Success, Try }

/**
 * Media-aware extraction: reads a file and returns it as text, an image, audio samples
 * or video frames, discriminated by the MIME type Tika sniffs from its content.
 *
 * Kept separate from [[DocumentExtractor]] on purpose. Document loading wants text and
 * metadata from anything text-bearing; multimodal embedding wants the decoded media
 * itself. The two share only the initial Tika sniff, and the audio and video cases
 * overlap `llm4s-speech` and `llm4s-image` rather than RAG, so this may not stay here.
 */
object MediaExtractor {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Extracted content from a file, discriminated by media type. */
  sealed trait Extracted

  /** Extracted text content (from PDF, DOCX, plain text, etc.). */
  final case class TextContent(text: String) extends Extracted

  /** Extracted image content. */
  final case class ImageContent(image: BufferedImage) extends Extracted

  /** Extracted audio content as mono PCM samples with a sample rate. */
  final case class AudioContent(samples: Array[Float], sampleRate: Int) extends Extracted

  /** Extracted video content as a sequence of frames at a given frame rate. */
  final case class VideoContent(frames: Seq[BufferedImage], fps: Int) extends Extracted

  /**
   * Extract content from a file, returning the [[Extracted]] variant matching its media type.
   *
   * @param inputPath path to the file (surrounding whitespace and quotes are stripped)
   * @return the extracted content, or a [[org.llm4s.error.ProcessingError]]
   */
  def extractAny(inputPath: String): Result[Extracted] = {
    val file = TikaDocumentExtractor.normalizePath(inputPath)
    if (!file.exists() || !file.isFile)
      Left(ProcessingError("media-extraction", s"File not found: ${file.getPath}"))
    else {
      val mimeType = TikaDocumentExtractor.detectMimeType(file)
      logger.debug(s"[ExtractAny] Processing: ${file.getPath} (MIME: $mimeType)")

      if (mimeType.startsWith("image/")) extractImage(file)
      else if (mimeType.startsWith("audio/"))
        unsupported("audio", file, s"Audio extraction not yet implemented (MIME: $mimeType)")
      else if (mimeType.startsWith("video/"))
        unsupported("video", file, s"Video extraction not yet implemented (MIME: $mimeType)")
      else TikaDocumentExtractor.extractFromPath(inputPath).map(doc => TextContent(doc.text))
    }
  }

  private def extractImage(file: File): Result[Extracted] =
    Try(ImageIO.read(file)) match {
      case Success(img) if img != null =>
        logger.debug(s"[Image] Loaded: ${img.getWidth}x${img.getHeight}")
        Right(ImageContent(img))
      case Success(_) =>
        logger.error(s"[Image] ImageIO returned null for: ${file.getPath}")
        Left(ProcessingError("image-extraction", s"ImageIO could not read '${file.getPath}'"))
      case Failure(ex) =>
        logger.error(s"[Image] Read failed: ${ex.getMessage}", ex)
        Left(
          ProcessingError("image-extraction", s"Image read failed for '${file.getPath}': ${ex.getMessage}", Some(ex))
        )
    }

  private def unsupported(kind: String, file: File, msg: String): Result[Nothing] = {
    logger.warn(s"[$kind] $msg")
    Left(ProcessingError(s"$kind-extraction", s"$msg (file: ${file.getPath})"))
  }
}
