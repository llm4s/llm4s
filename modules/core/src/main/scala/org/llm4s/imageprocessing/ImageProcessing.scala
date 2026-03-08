package org.llm4s.imageprocessing

import org.llm4s.imageprocessing.config._
import org.llm4s.imageprocessing.provider._
import org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient
import org.llm4s.error.LLMError

import scala.concurrent.{ Future, ExecutionContext, blocking }

/**
 * Factory object for creating image processing clients.
 *
 * This provides a unified API for image preprocessing capabilities including:
 * - Image format conversion and resizing
 * - Image analysis and description generation
 * - Integration with vision-enabled LLMs
 */
object ImageProcessing {

  /**
   * Creates an OpenAI Vision client for image analysis.
   *
   * @param apiKey OpenAI API key
   * @param model Vision model to use (default: gpt-4-vision-preview)
   * @return ImageProcessingClient instance
   */
  def openAIVisionClient(
    apiKey: String,
    model: String = "gpt-4-vision-preview"
  ): ImageProcessingClient = {
    val config = OpenAIVisionConfig(apiKey, model)
    new OpenAIVisionClient(config)
  }

  /**
   * Creates an Anthropic Claude Vision client for image analysis.
   *
   * @param apiKey Anthropic API key
   * @param model Claude model to use (default: claude-3-sonnet-20240229)
   * @return ImageProcessingClient instance
   */
  def anthropicVisionClient(
    apiKey: String,
    model: String = "claude-3-sonnet-20240229"
  ): ImageProcessingClient = {
    val config = AnthropicVisionConfig(apiKey, model)
    new AnthropicVisionClient(config)
  }

  /**
   * Creates a local image processor for basic image operations.
   * This doesn't require external API calls.
   *
   * @return ImageProcessingClient instance
   */
  def localImageProcessor(): ImageProcessingClient =
    new LocalImageProcessor()
}

/**
 * Main interface for image processing operations.
 */
trait ImageProcessingClient {

  /**
   * Analyzes an image and returns a description.
   *
   * @param imagePath Path to the image file
   * @param prompt Optional prompt to guide the analysis
   * @return Either error or image analysis result
   */
  def analyzeImage(
    imagePath: String,
    prompt: Option[String] = None
  ): Either[LLMError, ImageAnalysisResult]

  /**
   * Asynchronously analyzes an image and returns a description.
   *
   * Default implementation wraps the synchronous call in a Future
   * with an explicit blocking boundary to avoid starving compute pools.
   *
   * @param imagePath Path to the image file
   * @param prompt Optional prompt to guide the analysis
   * @param ec ExecutionContext for async execution
   * @return Future containing either error or image analysis result
   */
  def analyzeImageAsync(
    imagePath: String,
    prompt: Option[String] = None
  )(implicit ec: ExecutionContext): Future[Either[LLMError, ImageAnalysisResult]] =
    Future {
      blocking {
        analyzeImage(imagePath, prompt)
      }
    }.recover { case ex => Left(org.llm4s.error.ThrowableOps.RichThrowable(ex).toLLMError) }

  /**
   * Preprocesses an image with specified operations.
   *
   * @param imagePath Path to the input image
   * @param operations List of preprocessing operations to apply
   * @return Either error or processed image
   */
  def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage]

  /**
   * Converts image format.
   *
   * @param imagePath Path to the input image
   * @param targetFormat Target image format
   * @return Either error or converted image
   */
  def convertFormat(
    imagePath: String,
    targetFormat: ImageFormat
  ): Either[LLMError, ProcessedImage]

  /**
   * Resizes an image to specified dimensions.
   *
   * @param imagePath Path to the input image
   * @param width Target width
   * @param height Target height
   * @param maintainAspectRatio Whether to maintain aspect ratio
   * @return Either error or resized image
   */
  def resizeImage(
    imagePath: String,
    width: Int,
    height: Int,
    maintainAspectRatio: Boolean = true
  ): Either[LLMError, ProcessedImage]
}
