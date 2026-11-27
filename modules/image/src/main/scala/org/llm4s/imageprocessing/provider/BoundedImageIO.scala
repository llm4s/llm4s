package org.llm4s.imageprocessing.provider

import org.llm4s.error.{ InvalidInputError, LLMError, ProcessingError }

import java.awt.image.BufferedImage
import java.nio.file.{ Files, Path, Paths }
import javax.imageio.{ ImageIO, ImageReader }
import javax.imageio.stream.ImageInputStream
import scala.util.{ Failure, Try }
import scala.util.control.Exception.catching

/**
 * Image reads with limits (#3139): a file's size and its declared width and height are checked from the
 * header before anything is decoded, so a small file declaring 50000 x 50000 pixels is refused instead of
 * allocating gigabytes. Every failure, an `OutOfMemoryError` from an allocation included, is a `Left`.
 */
private[imageprocessing] object BoundedImageIO {

  /** The largest image file a vision client uploads: 20 MiB, above every vendor's own inline limit. */
  val MaxUploadBytes: Long = 20L * 1024 * 1024

  /** What the header says about an image, read without decoding its pixels. */
  final case class Header(width: Int, height: Int, hasAlpha: Boolean, numComponents: Int)

  /** The header of the image at `imagePath`, refusing a file over `maxBytes`. Nothing is decoded. */
  def header(imagePath: String, operation: String, maxBytes: Long): Either[LLMError, Header] =
    withReader(imagePath, operation, maxBytes) { reader =>
      val width  = reader.getWidth(0)
      val height = reader.getHeight(0)
      val colour = Option(reader.getRawImageType(0))
        .orElse {
          val types = reader.getImageTypes(0)
          if (types.hasNext) Some(types.next()) else None
        }
        .map(_.getColorModel)
      Right(Header(width, height, colour.exists(_.hasAlpha), colour.map(_.getNumComponents).getOrElse(3)))
    }

  /** Decodes the image at `imagePath`, refusing a file over `maxBytes` or an image over `maxPixels`. */
  def read(imagePath: String, operation: String, maxBytes: Long, maxPixels: Long): Either[LLMError, BufferedImage] =
    withReader(imagePath, operation, maxBytes) { reader =>
      checkPixels(operation, reader.getWidth(0), reader.getHeight(0), maxPixels).flatMap { _ =>
        guarded(operation)(reader.read(0))
      }
    }

  /** Reads at most `maxBytes` of the file at `imagePath`, refusing a larger one. */
  def readBytes(imagePath: String, maxBytes: Long): Try[Array[Byte]] =
    Try(Paths.get(imagePath)).flatMap { path =>
      Try(Files.size(path)).flatMap { size =>
        if (size > maxBytes)
          Failure(new IllegalArgumentException(s"Image file is $size bytes, over the $maxBytes-byte limit for upload"))
        else Try(Files.readAllBytes(path))
      }
    }

  /** A new image of `width` x `height`, refusing an empty side or more than `maxPixels` pixels. */
  def allocate(
    operation: String,
    width: Int,
    height: Int,
    imageType: Int,
    maxPixels: Long
  ): Either[LLMError, BufferedImage] =
    checkPixels(operation, width, height, maxPixels).flatMap(_ =>
      guarded(operation)(new BufferedImage(width, height, imageType))
    )

  def checkPixels(operation: String, width: Int, height: Int, maxPixels: Long): Either[LLMError, Unit] =
    if (width <= 0 || height <= 0)
      Left(InvalidInputError(operation, s"${width}x$height", "Width and height must be > 0"))
    else if (width.toLong * height.toLong > maxPixels)
      Left(InvalidInputError(operation, s"${width}x$height", s"Image is over the $maxPixels-pixel limit"))
    else Right(())

  /**
   * Runs `body`, returning any non-fatal exception, and an `OutOfMemoryError` (a failed allocation, which
   * leaves nothing half-done), as a `ProcessingError`.
   */
  def guarded[A](operation: String)(body: => A): Either[LLMError, A] =
    catching(classOf[OutOfMemoryError])
      .either(Try(body).toEither)
      .flatten
      .left
      .map(e => ProcessingError(operation, s"${e.getClass.getSimpleName}: ${e.getMessage}", Some(e)))

  private def withReader[A](imagePath: String, operation: String, maxBytes: Long)(
    use: ImageReader => Either[LLMError, A]
  ): Either[LLMError, A] =
    for {
      path <- guarded(operation)(Paths.get(imagePath))
      _    <- checkSize(path, imagePath, operation, maxBytes)
      stream <- guarded(operation)(Option(ImageIO.createImageInputStream(path.toFile)))
        .flatMap(_.toRight(ProcessingError(operation, s"Error reading image: cannot open $imagePath")))
      result <- {
        val outcome = readerFor(stream, imagePath).flatMap { reader =>
          val used = guarded(operation)(use(reader)).flatten
          reader.dispose()
          used
        }
        Try(stream.close())
        outcome
      }
    } yield result

  private def checkSize(path: Path, imagePath: String, operation: String, maxBytes: Long): Either[LLMError, Unit] =
    guarded(operation)(Files.size(path)).left
      .map(e => ProcessingError(operation, s"Error reading image: ${e.message}"))
      .flatMap { size =>
        if (size > maxBytes)
          Left(InvalidInputError("path", imagePath, s"Image file is $size bytes, over the $maxBytes-byte limit"))
        else Right(())
      }

  private def readerFor(stream: ImageInputStream, imagePath: String): Either[LLMError, ImageReader] = {
    val readers = ImageIO.getImageReaders(stream)
    if (!readers.hasNext) Left(InvalidInputError("path", imagePath, "Could not read image from path"))
    else {
      val reader = readers.next()
      reader.setInput(stream, true, true)
      Right(reader)
    }
  }
}
