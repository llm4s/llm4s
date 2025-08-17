package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing._
import org.llm4s.error.LLMError
import java.awt.image.BufferedImage
import java.awt.{ RenderingHints, Color }
import java.io.{ ByteArrayOutputStream, File }
import javax.imageio.ImageIO
import java.time.Instant

/**
 * Local image processor that uses Java's built-in image processing capabilities.
 * This implementation doesn't require external API calls and provides basic
 * image manipulation operations.
 */
class LocalImageProcessor extends org.llm4s.imageprocessing.ImageProcessingClient {

  override def analyzeImage(
    imagePath: String,
    prompt: Option[String] = None
  ): Either[LLMError, ImageAnalysisResult] =
    // Local processor can only provide basic metadata analysis
    // For AI-powered analysis, use OpenAI or Anthropic clients
    try {
      val bufferedImage = ImageIO.read(new File(imagePath))
      if (bufferedImage == null) {
        return Left(LLMError.invalidImageInput("path", imagePath, "Could not read image from path"))
      }

      val metadata      = extractImageMetadata(imagePath, bufferedImage)
      val basicAnalysis = generateBasicAnalysis(bufferedImage, prompt)

      Right(
        ImageAnalysisResult(
          description = basicAnalysis,
          confidence = 0.5, // Low confidence for basic analysis
          tags = extractBasicTags(bufferedImage),
          objects = List.empty,  // No object detection in local processor
          emotions = List.empty, // No emotion detection in local processor
          text = None,           // No OCR in basic local processor
          metadata = metadata
        )
      )
    } catch {
      case e: Exception =>
        Left(LLMError.processingFailed("analyze", s"Error analyzing image: ${e.getMessage}", Some(e)))
    }

  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    try {
      val originalImage = ImageIO.read(new File(imagePath))
      if (originalImage == null) {
        return Left(LLMError.invalidImageInput("path", imagePath, "Could not read image from path"))
      }

      val processedImage = operations.foldLeft(originalImage)((img, operation) => applyOperation(img, operation))

      val format    = ImageFormat.PNG // Default output format
      val imageData = convertToByteArray(processedImage, format)
      val metadata = ImageMetadata(
        originalPath = Some(imagePath),
        processedAt = Instant.now(),
        operations = operations
      )

      Right(
        ProcessedImage(
          data = imageData,
          format = format,
          width = processedImage.getWidth,
          height = processedImage.getHeight,
          metadata = metadata
        )
      )
    } catch {
      case e: Exception =>
        Left(LLMError.processingFailed("process", s"Error processing image: ${e.getMessage}", Some(e)))
    }

  override def convertFormat(
    imagePath: String,
    targetFormat: ImageFormat
  ): Either[LLMError, ProcessedImage] =
    try {
      val originalImage = ImageIO.read(new File(imagePath))
      if (originalImage == null) {
        return Left(LLMError.invalidImageInput("path", imagePath, "Could not read image from path"))
      }

      val imageData = convertToByteArray(originalImage, targetFormat)
      val metadata = ImageMetadata(
        originalPath = Some(imagePath),
        processedAt = Instant.now(),
        operations = List.empty
      )

      Right(
        ProcessedImage(
          data = imageData,
          format = targetFormat,
          width = originalImage.getWidth,
          height = originalImage.getHeight,
          metadata = metadata
        )
      )
    } catch {
      case e: Exception =>
        Left(LLMError.processingFailed("convert", s"Error converting image format: ${e.getMessage}", Some(e)))
    }

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

  private def applyOperation(image: BufferedImage, operation: ImageOperation): BufferedImage =
    operation match {
      case ImageOperation.Resize(width, height, maintainAspectRatio) =>
        resizeBufferedImage(image, width, height, maintainAspectRatio)

      case ImageOperation.Crop(x, y, width, height) =>
        cropBufferedImage(image, x, y, width, height)

      case ImageOperation.Rotate(degrees) =>
        rotateBufferedImage(image, degrees)

      case ImageOperation.Blur(radius) =>
        blurBufferedImage(image, radius)

      case ImageOperation.Brightness(level) =>
        adjustBrightness(image, level)

      case ImageOperation.Contrast(level) =>
        adjustContrast(image, level)

      case ImageOperation.Grayscale =>
        convertToGrayscale(image)

      case ImageOperation.Normalize =>
        normalizeImage(image)
    }

  private def resizeBufferedImage(
    image: BufferedImage,
    targetWidth: Int,
    targetHeight: Int,
    maintainAspectRatio: Boolean
  ): BufferedImage = {
    val (newWidth, newHeight) = if (maintainAspectRatio) {
      val aspectRatio = image.getWidth.toDouble / image.getHeight.toDouble
      if (targetWidth / aspectRatio <= targetHeight) {
        (targetWidth, (targetWidth / aspectRatio).toInt)
      } else {
        ((targetHeight * aspectRatio).toInt, targetHeight)
      }
    } else {
      (targetWidth, targetHeight)
    }

    val resizedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
    val g2d          = resizedImage.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g2d.drawImage(image, 0, 0, newWidth, newHeight, null)
    g2d.dispose()
    resizedImage
  }

  private def cropBufferedImage(image: BufferedImage, x: Int, y: Int, width: Int, height: Int): BufferedImage = {
    val croppedImage = image.getSubimage(
      math.max(0, x),
      math.max(0, y),
      math.min(width, image.getWidth - x),
      math.min(height, image.getHeight - y)
    )

    // Create a new BufferedImage to avoid issues with subimage
    val result = new BufferedImage(croppedImage.getWidth, croppedImage.getHeight, BufferedImage.TYPE_INT_RGB)
    val g2d    = result.createGraphics()
    g2d.drawImage(croppedImage, 0, 0, null)
    g2d.dispose()
    result
  }

  private def rotateBufferedImage(image: BufferedImage, degrees: Double): BufferedImage = {
    val radians = Math.toRadians(degrees)
    val sin     = Math.abs(Math.sin(radians))
    val cos     = Math.abs(Math.cos(radians))

    val newWidth  = (image.getWidth * cos + image.getHeight * sin).toInt
    val newHeight = (image.getWidth * sin + image.getHeight * cos).toInt

    val rotatedImage = new BufferedImage(newWidth, newHeight, BufferedImage.TYPE_INT_RGB)
    val g2d          = rotatedImage.createGraphics()
    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    g2d.translate(newWidth / 2, newHeight / 2)
    g2d.rotate(radians)
    g2d.translate(-image.getWidth / 2, -image.getHeight / 2)
    g2d.drawImage(image, 0, 0, null)
    g2d.dispose()
    rotatedImage
  }

  private def blurBufferedImage(image: BufferedImage, radius: Double): BufferedImage = {
    val kernelSize = math.max(3, (radius * 2 + 1).toInt)
    val halfKernel = kernelSize / 2

    val result = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)

    // Apply box blur using convolution
    for {
      y <- 0 until image.getHeight
      x <- 0 until image.getWidth
    } {
      // Sample pixels in the kernel area using foldLeft for immutability
      val kernelPixels = for {
        ky <- math.max(0, y - halfKernel) to math.min(image.getHeight - 1, y + halfKernel)
        kx <- math.max(0, x - halfKernel) to math.min(image.getWidth - 1, x + halfKernel)
      } yield image.getRGB(kx, ky)

      val (redSum, greenSum, blueSum, count) = kernelPixels.foldLeft((0, 0, 0, 0)) { case ((r, g, b, c), rgb) =>
        (r + ((rgb >> 16) & 0xff), g + ((rgb >> 8) & 0xff), b + (rgb & 0xff), c + 1)
      }

      // Calculate average color
      val avgRed   = redSum / count
      val avgGreen = greenSum / count
      val avgBlue  = blueSum / count

      result.setRGB(x, y, (avgRed << 16) | (avgGreen << 8) | avgBlue)
    }

    result
  }

  private def adjustBrightness(image: BufferedImage, level: Int): BufferedImage = {
    val result     = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
    val adjustment = level / 100.0f

    for {
      x <- 0 until image.getWidth
      y <- 0 until image.getHeight
    } {
      val rgb      = image.getRGB(x, y)
      val color    = new Color(rgb)
      val newRed   = math.max(0, math.min(255, (color.getRed + adjustment * 255).toInt))
      val newGreen = math.max(0, math.min(255, (color.getGreen + adjustment * 255).toInt))
      val newBlue  = math.max(0, math.min(255, (color.getBlue + adjustment * 255).toInt))
      result.setRGB(x, y, new Color(newRed, newGreen, newBlue).getRGB)
    }
    result
  }

  private def adjustContrast(image: BufferedImage, level: Int): BufferedImage = {
    val result = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_INT_RGB)
    val factor = (259.0 * (level + 255)) / (255 * (259 - level))

    for {
      x <- 0 until image.getWidth
      y <- 0 until image.getHeight
    } {
      val rgb      = image.getRGB(x, y)
      val color    = new Color(rgb)
      val newRed   = math.max(0, math.min(255, (factor * (color.getRed - 128) + 128).toInt))
      val newGreen = math.max(0, math.min(255, (factor * (color.getGreen - 128) + 128).toInt))
      val newBlue  = math.max(0, math.min(255, (factor * (color.getBlue - 128) + 128).toInt))
      result.setRGB(x, y, new Color(newRed, newGreen, newBlue).getRGB)
    }
    result
  }

  private def convertToGrayscale(image: BufferedImage): BufferedImage = {
    val result = new BufferedImage(image.getWidth, image.getHeight, BufferedImage.TYPE_BYTE_GRAY)
    val g2d    = result.createGraphics()
    g2d.drawImage(image, 0, 0, null)
    g2d.dispose()
    result
  }

  private def normalizeImage(image: BufferedImage): BufferedImage =
    // Simple normalization - just return the original for now
    image

  private def convertToByteArray(image: BufferedImage, format: ImageFormat): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val formatName = format match {
      case ImageFormat.PNG  => "png"
      case ImageFormat.JPEG => "jpg"
      case ImageFormat.WEBP => "png" // Fallback to PNG for WEBP
      case ImageFormat.GIF  => "gif"
    }
    ImageIO.write(image, formatName, baos)
    baos.toByteArray
  }

  private def extractImageMetadata(imagePath: String, image: BufferedImage): ImageMetadata =
    ImageMetadata(
      originalPath = Some(imagePath),
      createdAt = Instant.now(),
      processedAt = Instant.now(),
      operations = List.empty,
      colorSpace = Some(if (image.getColorModel.getNumComponents == 1) "GRAYSCALE" else "RGB"),
      hasAlpha = image.getColorModel.hasAlpha
    )

  private def generateBasicAnalysis(image: BufferedImage, prompt: Option[String]): String = {
    val width           = image.getWidth
    val height          = image.getHeight
    val aspectRatio     = width.toDouble / height.toDouble
    val hasAlpha        = image.getColorModel.hasAlpha
    val colorComponents = image.getColorModel.getNumComponents

    val orientation =
      if (aspectRatio > 1.3) "landscape"
      else if (aspectRatio < 0.77) "portrait"
      else "square"

    val colorDescription = if (colorComponents == 1) "grayscale" else "color"

    s"A ${width}x${height} ${colorDescription} image in ${orientation} orientation" +
      (if (hasAlpha) " with transparency" else "") +
      prompt.map(p => s". Analysis prompt: $p").getOrElse("")
  }

  private def extractBasicTags(image: BufferedImage): List[String] = {
    val tags = scala.collection.mutable.ListBuffer[String]()

    val width       = image.getWidth
    val height      = image.getHeight
    val aspectRatio = width.toDouble / height.toDouble

    // Basic size tags
    if (width * height > 2000000) tags += "high-resolution"
    else if (width * height < 100000) tags += "low-resolution"

    // Orientation tags
    if (aspectRatio > 1.3) tags += "landscape"
    else if (aspectRatio < 0.77) tags += "portrait"
    else tags += "square"

    // Color tags
    if (image.getColorModel.getNumComponents == 1) tags += "grayscale"
    else tags += "color"

    if (image.getColorModel.hasAlpha) tags += "transparent"

    tags.toList
  }
}
