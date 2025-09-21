package org.llm4s.imageprocessing

import java.nio.file.Path
import java.time.Instant
import org.llm4s.error.LLMError
import cats.data.Validated
import cats.syntax.validated._
import scala.util.Try

/**
 * Represents different image formats supported by the system.
 */
sealed trait ImageFormat {
  def extension: String
  def mimeType: String
}

object ImageFormat {
  case object PNG extends ImageFormat {
    override def extension: String = "png"
    override def mimeType: String  = "image/png"
  }

  case object JPEG extends ImageFormat {
    override def extension: String = "jpg"
    override def mimeType: String  = "image/jpeg"
  }

  case object WEBP extends ImageFormat {
    override def extension: String = "webp"
    override def mimeType: String  = "image/webp"
  }

  case object GIF extends ImageFormat {
    override def extension: String = "gif"
    override def mimeType: String  = "image/gif"
  }
}

/**
 * Represents various image processing operations.
 */
sealed trait ImageOperation

object ImageOperation {

  /**
   * Resize operation with specific dimensions.
   */
  case class Resize(width: Int, height: Int, maintainAspectRatio: Boolean = true) extends ImageOperation

  /**
   * Crop operation with coordinates and dimensions.
   */
  case class Crop(x: Int, y: Int, width: Int, height: Int) extends ImageOperation

  /**
   * Rotate operation with angle in degrees.
   */
  case class Rotate(degrees: Double) extends ImageOperation

  /**
   * Apply blur filter.
   */
  case class Blur(radius: Double) extends ImageOperation

  /**
   * Adjust brightness (-100 to 100).
   */
  case class Brightness(level: Int) extends ImageOperation

  /**
   * Adjust contrast (-100 to 100).
   */
  case class Contrast(level: Int) extends ImageOperation

  /**
   * Convert to grayscale.
   */
  case object Grayscale extends ImageOperation

  /**
   * Normalize image (0-255 pixel values).
   */
  case object Normalize extends ImageOperation
}

/**
 * Represents a processed image with metadata.
 */
case class ProcessedImage(
  data: Array[Byte],
  format: ImageFormat,
  width: Int,
  height: Int,
  metadata: ImageMetadata
) {

  /**
   * Save the processed image to a file.
   */
  def saveToFile(path: Path): Either[LLMError, Unit] = {
    import java.nio.file.Files

    // Use cats Validated for path validation, then safely write
    validatePath(path).toEither.flatMap { normalizedPath =>
      Try(Files.write(normalizedPath, data)).toEither.left
        .map {
          case e: java.nio.file.AccessDeniedException =>
            LLMError.processingFailed("save", s"Access denied: ${e.getMessage}", Some(e))
          case e: java.nio.file.NoSuchFileException =>
            LLMError.processingFailed("save", s"Directory does not exist: ${e.getMessage}", Some(e))
          case e: java.nio.file.FileSystemException =>
            LLMError.processingFailed("save", s"File system error: ${e.getMessage}", Some(e))
          case e: Exception =>
            LLMError.processingFailed("save", s"Unexpected error: ${e.getMessage}", Some(e))
        }
        .map(_ => ())
    }
  }

  /**
   * Validate path for security using cats Validated.
   */
  private def validatePath(path: Path): Validated[LLMError, Path] = {
    val normalizedPath = path.normalize()
    val currentDir     = path.getFileSystem.getPath(".").normalize()

    if (
      normalizedPath.startsWith(currentDir.resolve("..")) ||
      normalizedPath.startsWith(currentDir.resolve("../"))
    ) {
      LLMError.invalidImageInput("path", path.toString, "Path traversal detected").invalid
    } else {
      normalizedPath.valid
    }
  }

  /**
   * Get image size in bytes.
   */
  def sizeInBytes: Long = data.length
}

/**
 * Metadata associated with an image.
 */
case class ImageMetadata(
  originalPath: Option[String] = None,
  createdAt: Instant = Instant.now(),
  processedAt: Instant = Instant.now(),
  operations: List[ImageOperation] = List.empty,
  colorSpace: Option[String] = None,
  dpi: Option[Int] = None,
  hasAlpha: Boolean = false,
  compressionRatio: Option[Double] = None
)

/**
 * Result of image analysis operations.
 */
case class ImageAnalysisResult(
  description: String,
  confidence: Double,
  tags: List[String] = List.empty,
  objects: List[DetectedObject] = List.empty,
  emotions: List[DetectedEmotion] = List.empty,
  text: Option[String] = None, // OCR results
  metadata: ImageMetadata
)

/**
 * Represents a detected object in an image.
 */
case class DetectedObject(
  label: String,
  confidence: Double,
  boundingBox: BoundingBox
)

/**
 * Represents detected emotions in an image.
 */
case class DetectedEmotion(
  emotion: String,
  confidence: Double
)

/**
 * Bounding box coordinates for detected objects.
 */
case class BoundingBox(
  x: Int,
  y: Int,
  width: Int,
  height: Int
)

/**
 * Image embeddings for similarity search and multimodal operations.
 */
case class ImageEmbedding(
  vector: Array[Float],
  dimensions: Int,
  model: String,
  imagePath: String,
  metadata: ImageMetadata
) {

  /**
   * Calculate cosine similarity with another embedding.
   */
  def cosineSimilarity(other: ImageEmbedding): Double = {
    require(this.dimensions == other.dimensions, "Embedding dimensions must match")

    val dotProduct = vector.zip(other.vector).map { case (a, b) => a * b }.sum
    val normA      = math.sqrt(vector.map(x => x * x).sum)
    val normB      = math.sqrt(other.vector.map(x => x * x).sum)

    dotProduct / (normA * normB)
  }
}

/**
 * Options for image embedding generation.
 */
case class ImageEmbeddingOptions(
  model: String = "clip-vit-base-patch32",
  normalize: Boolean = true,
  batchSize: Int = 1
)
