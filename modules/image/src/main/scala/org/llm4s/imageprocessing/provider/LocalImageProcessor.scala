package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing._
import org.llm4s.error.{ InvalidInputError, LLMError }
import org.llm4s.media.{ ImageMediaType, MediaType }

import java.awt.image.BufferedImage
import java.awt.RenderingHints
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import java.time.Instant

/**
 * Local image processor that uses Java's built-in image processing capabilities.
 * This implementation doesn't require external API calls and provides basic
 * image manipulation operations.
 *
 * An image is read with limits: a file over `maxFileBytes`, or one whose header declares more than
 * `maxPixels` pixels, is refused with `InvalidInputError` before it is decoded, and so is an operation
 * whose result would be over `maxPixels`. `analyzeImage` reads only the header. Every failure is a `Left`.
 *
 * @param maxPixels the largest width x height decoded or produced; 50 million by default
 * @param maxFileBytes the largest file read; 50 MiB by default
 */
class LocalImageProcessor(
  maxPixels: Long = LocalImageProcessor.DefaultMaxPixels,
  maxFileBytes: Long = LocalImageProcessor.DefaultMaxFileBytes
) extends org.llm4s.imageprocessing.ImageProcessingClient {

  override def analyzeImage(
    imagePath: String,
    prompt: Option[String] = None
  ): Either[LLMError, ImageAnalysisResult] =
    // Local processor can only provide basic metadata analysis, from the header: nothing is decoded.
    // For AI-powered analysis, use OpenAI or Anthropic clients
    BoundedImageIO.header(imagePath, "analyze", maxFileBytes).map { header =>
      ImageAnalysisResult(
        description = generateBasicAnalysis(header, prompt),
        confidence = 0.5, // Low confidence for basic analysis
        tags = extractBasicTags(header),
        objects = List.empty,
        emotions = List.empty,
        text = None,
        metadata = extractImageMetadata(imagePath, header)
      )
    }

  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    for {
      originalImage <- BoundedImageIO.read(imagePath, "process", maxFileBytes, maxPixels)
      processedImage <- operations.foldLeft[Either[LLMError, BufferedImage]](Right(originalImage)) { case (acc, op) =>
        acc.flatMap(img => BoundedImageIO.guarded("process")(applyOperationEither(img, op)).flatten)
      }
      format = MediaType.Png // Default output format
      imageData <- convertToByteArray(processedImage, format)
    } yield ProcessedImage(
      data = imageData,
      format = format,
      width = processedImage.getWidth,
      height = processedImage.getHeight,
      metadata = ImageMetadata(
        originalPath = Some(imagePath),
        processedAt = Instant.now(),
        operations = operations
      )
    )

  override def convertFormat(
    imagePath: String,
    targetFormat: ImageMediaType
  ): Either[LLMError, ProcessedImage] =
    for {
      originalImage <- BoundedImageIO.read(imagePath, "convert", maxFileBytes, maxPixels)
      imageData     <- convertToByteArray(originalImage, targetFormat)
    } yield ProcessedImage(
      data = imageData,
      format = targetFormat,
      width = originalImage.getWidth,
      height = originalImage.getHeight,
      metadata = ImageMetadata(
        originalPath = Some(imagePath),
        processedAt = Instant.now(),
        operations = List.empty
      )
    )

  override def resizeImage(
    imagePath: String,
    width: Int,
    height: Int,
    maintainAspectRatio: Boolean = true
  ): Either[LLMError, ProcessedImage] = {
    val resizeOperation = ImageOperation.Resize(width, height, maintainAspectRatio)
    preprocessImage(imagePath, List(resizeOperation))
  }

  // Private helper methods

  private def allocate(operation: String, width: Int, height: Int, imageType: Int = BufferedImage.TYPE_INT_RGB) =
    BoundedImageIO.allocate(operation, width, height, imageType, maxPixels)

  private def applyOperationEither(image: BufferedImage, operation: ImageOperation): Either[LLMError, BufferedImage] =
    operation match {
      case ImageOperation.Resize(width, height, maintainAspectRatio) =>
        if (width <= 0 || height <= 0)
          Left(InvalidInputError("resize", s"${width}x${height}", "Width and height must be > 0"))
        else resizeBufferedImage(image, width, height, maintainAspectRatio)

      case ImageOperation.Crop(x, y, width, height) =>
        val withinBounds = x >= 0 && y >= 0 && width > 0 && height > 0 &&
          (x.toLong + width) <= image.getWidth && (y.toLong + height) <= image.getHeight
        if (!withinBounds)
          Left(InvalidInputError("crop", s"x=$x y=$y w=$width h=$height", "Crop rectangle out of bounds"))
        else cropBufferedImage(image, x, y, width, height)

      case ImageOperation.Rotate(degrees) =>
        if (degrees.isNaN || degrees.isInfinite)
          Left(InvalidInputError("rotate", degrees.toString, "Angle must be a finite number of degrees"))
        else rotateBufferedImage(image, degrees)

      case ImageOperation.Blur(radius) =>
        if (radius.isNaN || radius < 0 || radius > LocalImageProcessor.MaxBlurRadius)
          Left(
            InvalidInputError("blur", radius.toString, s"Radius must be from 0 to ${LocalImageProcessor.MaxBlurRadius}")
          )
        else blurBufferedImage(image, radius)

      case ImageOperation.Brightness(level) =>
        if (level < -100 || level > 100)
          Left(InvalidInputError("brightness", level.toString, "Level must be -100 to 100"))
        else adjustBrightness(image, level)

      case ImageOperation.Contrast(level) =>
        if (level < -100 || level > 100)
          Left(InvalidInputError("contrast", level.toString, "Level must be -100 to 100"))
        else adjustContrast(image, level)

      case ImageOperation.Grayscale =>
        convertToGrayscale(image)

      case ImageOperation.Normalize =>
        Right(normalizeImage(image))
    }

  private def resizeBufferedImage(
    image: BufferedImage,
    targetWidth: Int,
    targetHeight: Int,
    maintainAspectRatio: Boolean
  ): Either[LLMError, BufferedImage] = {
    val (newWidth, newHeight) = if (maintainAspectRatio) {
      val aspectRatio = image.getWidth.toDouble / image.getHeight.toDouble
      // A very elongated image fitted into a small box keeps at least one pixel on its short side.
      if (targetWidth / aspectRatio <= targetHeight) {
        (targetWidth, math.max(1, (targetWidth / aspectRatio).toInt))
      } else {
        (math.max(1, (targetHeight * aspectRatio).toInt), targetHeight)
      }
    } else {
      (targetWidth, targetHeight)
    }

    allocate("resize", newWidth, newHeight).map { resizedImage =>
      val g2d = resizedImage.createGraphics()
      g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g2d.drawImage(image, 0, 0, newWidth, newHeight, null)
      g2d.dispose()
      resizedImage
    }
  }

  private def cropBufferedImage(
    image: BufferedImage,
    x: Int,
    y: Int,
    width: Int,
    height: Int
  ): Either[LLMError, BufferedImage] = {
    val croppedImage = image.getSubimage(x, y, width, height)

    // Create a new BufferedImage to avoid issues with subimage
    allocate("crop", croppedImage.getWidth, croppedImage.getHeight).map { result =>
      val g2d = result.createGraphics()
      g2d.drawImage(croppedImage, 0, 0, null)
      g2d.dispose()
      result
    }
  }

  private def rotateBufferedImage(image: BufferedImage, degrees: Double): Either[LLMError, BufferedImage] = {
    val radians = Math.toRadians(degrees)
    val sin     = Math.abs(Math.sin(radians))
    val cos     = Math.abs(Math.cos(radians))

    val newWidth  = math.max(1, (image.getWidth * cos + image.getHeight * sin).toInt)
    val newHeight = math.max(1, (image.getWidth * sin + image.getHeight * cos).toInt)

    allocate("rotate", newWidth, newHeight).map { rotatedImage =>
      val g2d = rotatedImage.createGraphics()
      g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
      g2d.translate(newWidth / 2, newHeight / 2)
      g2d.rotate(radians)
      g2d.translate(-image.getWidth / 2, -image.getHeight / 2)
      g2d.drawImage(image, 0, 0, null)
      g2d.dispose()
      rotatedImage
    }
  }

  /**
   * A box blur, as two one-dimensional passes with running sums: O(width x height) whatever the radius.
   * Each output pixel is the average of the in-bounds pixels in its window, as for a direct 2-D box.
   */
  private def blurBufferedImage(image: BufferedImage, radius: Double): Either[LLMError, BufferedImage] = {
    val kernelSize = math.max(3, (radius * 2 + 1).toInt)
    val half       = kernelSize / 2
    val width      = image.getWidth
    val height     = image.getHeight

    allocate("blur", width, height).map { result =>
      val source     = image.getRGB(0, 0, width, height, null, 0, width)
      val horizontal = new Array[Int](source.length)
      boxPass(source, horizontal, lines = height, length = width, stride = 1, lineStride = width, half)
      val vertical = new Array[Int](source.length)
      boxPass(horizontal, vertical, lines = width, length = height, stride = width, lineStride = 1, half)
      result.setRGB(0, 0, width, height, vertical, 0, width)
      result
    }
  }

  /** Averages each of `lines` lines of `length` pixels over a window of `half` on each side, per channel. */
  private def boxPass(
    in: Array[Int],
    out: Array[Int],
    lines: Int,
    length: Int,
    stride: Int,
    lineStride: Int,
    half: Int
  ): Unit =
    (0 until lines).foreach { line =>
      val base       = line * lineStride
      def at(i: Int) = in(base + i * stride)
      var r, g, b    = 0L
      var count      = 0
      // The window of position 0 is [0, half].
      (0 to math.min(half, length - 1)).foreach { i =>
        val p = at(i); r += (p >> 16) & 0xff; g += (p >> 8) & 0xff; b += p & 0xff; count += 1
      }
      (0 until length).foreach { i =>
        out(base + i * stride) = ((r / count).toInt << 16) | ((g / count).toInt << 8) | (b / count).toInt
        val leaving  = i - half
        val entering = i + half + 1
        if (leaving >= 0) {
          val p = at(leaving); r -= (p >> 16) & 0xff; g -= (p >> 8) & 0xff; b -= p & 0xff; count -= 1
        }
        if (entering < length) {
          val p = at(entering); r += (p >> 16) & 0xff; g += (p >> 8) & 0xff; b += p & 0xff; count += 1
        }
      }
    }

  private def adjustBrightness(image: BufferedImage, level: Int): Either[LLMError, BufferedImage] = {
    val adjustment = (level / 100.0f * 255).toInt
    mapChannels("brightness", image)(c => c + adjustment)
  }

  private def adjustContrast(image: BufferedImage, level: Int): Either[LLMError, BufferedImage] = {
    // level is -100..100, so 259 - level is never 0.
    val factor = (259.0 * (level + 255)) / (255 * (259 - level))
    mapChannels("contrast", image)(c => (factor * (c - 128) + 128).toInt)
  }

  /** Applies `f` to each colour channel of every pixel, clamping to 0..255, into a new RGB image. */
  private def mapChannels(operation: String, image: BufferedImage)(f: Int => Int): Either[LLMError, BufferedImage] = {
    val width  = image.getWidth
    val height = image.getHeight
    allocate(operation, width, height).map { result =>
      val pixels          = image.getRGB(0, 0, width, height, null, 0, width)
      def channel(v: Int) = math.max(0, math.min(255, f(v)))
      pixels.indices.foreach { i =>
        val p = pixels(i)
        pixels(i) = (channel((p >> 16) & 0xff) << 16) | (channel((p >> 8) & 0xff) << 8) | channel(p & 0xff)
      }
      result.setRGB(0, 0, width, height, pixels, 0, width)
      result
    }
  }

  private def convertToGrayscale(image: BufferedImage): Either[LLMError, BufferedImage] =
    allocate("grayscale", image.getWidth, image.getHeight, BufferedImage.TYPE_BYTE_GRAY).map { result =>
      val g2d = result.createGraphics()
      g2d.drawImage(image, 0, 0, null)
      g2d.dispose()
      result
    }

  private def normalizeImage(image: BufferedImage): BufferedImage =
    // Simple normalization - just return the original for now
    image

  private def convertToByteArray(image: BufferedImage, format: ImageMediaType): Either[LLMError, Array[Byte]] = {
    // Every image media type's extension is also its ImageIO format name, except WebP, for
    // which the JDK ships no writer at all - those are written as PNG, as they were before.
    val formatName = format match {
      case MediaType.WebP => "png"
      case other          => other.extension
    }
    BoundedImageIO.guarded("encode") {
      val baos = new ByteArrayOutputStream()
      ImageIO.write(image, formatName, baos)
      baos.toByteArray
    }
  }

  private def extractImageMetadata(imagePath: String, image: BoundedImageIO.Header): ImageMetadata =
    ImageMetadata(
      originalPath = Some(imagePath),
      createdAt = Instant.now(),
      processedAt = Instant.now(),
      operations = List.empty,
      colorSpace = Some(if (image.numComponents == 1) "GRAYSCALE" else "RGB"),
      hasAlpha = image.hasAlpha
    )

  private def generateBasicAnalysis(image: BoundedImageIO.Header, prompt: Option[String]): String = {
    val width           = image.width
    val height          = image.height
    val aspectRatio     = width.toDouble / height.toDouble
    val hasAlpha        = image.hasAlpha
    val colorComponents = image.numComponents

    val orientation =
      if (aspectRatio > 1.3) "landscape"
      else if (aspectRatio < 0.77) "portrait"
      else "square"

    val colorDescription = if (colorComponents == 1) "grayscale" else "color"

    s"A ${width}x${height} ${colorDescription} image in ${orientation} orientation" +
      (if (hasAlpha) " with transparency" else "") +
      prompt.map(p => s". Analysis prompt: $p").getOrElse("")
  }

  private def extractBasicTags(image: BoundedImageIO.Header): List[String] = {
    val tags = scala.collection.mutable.ListBuffer[String]()

    val width       = image.width.toLong
    val height      = image.height.toLong
    val aspectRatio = width.toDouble / height.toDouble

    // Basic size tags
    if (width * height > 2000000) tags += "high-resolution"
    else if (width * height < 100000) tags += "low-resolution"

    // Orientation tags
    if (aspectRatio > 1.3) tags += "landscape"
    else if (aspectRatio < 0.77) tags += "portrait"
    else tags += "square"

    // Color tags
    if (image.numComponents == 1) tags += "grayscale"
    else tags += "color"

    if (image.hasAlpha) tags += "transparent"

    tags.toList
  }
}

object LocalImageProcessor {

  /** The default limit on width x height: 50 megapixels, about 200 MB decoded as ARGB. */
  val DefaultMaxPixels: Long = 50_000_000L

  /** The default limit on an image file's size: 50 MiB. */
  val DefaultMaxFileBytes: Long = 50L * 1024 * 1024

  /** The largest `Blur` radius accepted. */
  val MaxBlurRadius: Double = 100.0
}
