package org.llm4s.imageprocessing.provider.anthropicclient

import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.AnthropicVisionConfig
import org.llm4s.imageprocessing.provider.LocalImageProcessor
import org.llm4s.error.LLMError

import java.nio.file.{ Files, Paths }
import java.time.Instant
import java.util.Base64
import scala.util.Try

/**
 * Anthropic Claude Vision client for AI-powered image analysis.
 * This client provides advanced image understanding capabilities using Claude's vision models.
 */
class AnthropicVisionClient(config: AnthropicVisionConfig) extends org.llm4s.imageprocessing.ImageProcessingClient {

  private val localProcessor = new LocalImageProcessor()

  /**
   * Analyzes an image using Anthropic's Claude Vision API.
   *
   * @param imagePath Path to the image file to analyze
   * @param prompt Optional custom prompt for the analysis. If not provided, uses a default comprehensive prompt
   * @return Either an LLMError if the analysis fails, or an ImageAnalysisResult with the analysis details
   */
  override def analyzeImage(
    imagePath: String,
    prompt: Option[String] = None
  ): Either[LLMError, ImageAnalysisResult] =
    for {
      // First, get basic metadata using local processor
      basic <- localProcessor.analyzeImage(imagePath, None)
      metadata = basic.metadata
      // Convert image to base64 for API call
      base64Image <- encodeImageToBase64(imagePath).toEither.left
        .map(e => LLMError.processingFailed("encode", s"Failed to encode image: ${e.getMessage}", Some(e)))

      // Call Anthropic Vision API
      analysisPrompt = prompt.getOrElse(
        "Analyze this image in detail. Describe what you see, identify any objects, text, or people present. " +
          "Provide tags that categorize the image content."
      )
      // Detect media type for proper API call
      mediaType = detectMediaType(imagePath)
      visionResponse <- callAnthropicVisionAPI(base64Image, analysisPrompt, mediaType).toEither.left
        .map(e => LLMError.apiCallFailed("Anthropic", s"Anthropic Vision API call failed: ${e.getMessage}"))
    } yield parseVisionResponse(visionResponse, metadata) // Parse the response and extract structured information

  /**
   * Preprocesses an image by applying a sequence of operations.
   *
   * @param imagePath Path to the image file to preprocess
   * @param operations List of image operations to apply (resize, crop, rotate, etc.)
   * @return Either an LLMError if preprocessing fails, or a ProcessedImage with the result
   */
  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    // Delegate preprocessing to local processor
    localProcessor.preprocessImage(imagePath, operations)

  /**
   * Converts an image from one format to another.
   *
   * @param imagePath Path to the source image file
   * @param targetFormat The desired output format (JPEG, PNG, GIF, BMP)
   * @return Either an LLMError if conversion fails, or a ProcessedImage in the new format
   */
  override def convertFormat(
    imagePath: String,
    targetFormat: ImageFormat
  ): Either[LLMError, ProcessedImage] =
    // Delegate format conversion to local processor
    localProcessor.convertFormat(imagePath, targetFormat)

  /**
   * Resizes an image to specified dimensions.
   *
   * @param imagePath Path to the image file to resize
   * @param width Target width in pixels
   * @param height Target height in pixels
   * @param maintainAspectRatio If true, maintains the original aspect ratio (default: true)
   * @return Either an LLMError if resizing fails, or a ProcessedImage with new dimensions
   */
  override def resizeImage(
    imagePath: String,
    width: Int,
    height: Int,
    maintainAspectRatio: Boolean = true
  ): Either[LLMError, ProcessedImage] =
    // Delegate resizing to local processor
    localProcessor.resizeImage(imagePath, width, height, maintainAspectRatio)

  // Additional methods specific to Anthropic Vision

  /**
   * Performs Optical Character Recognition (OCR) on the image using Claude Vision.
   * Extracts and transcribes all visible text from the image.
   *
   * @param imagePath Path to the image file containing text
   * @return Either an LLMError if extraction fails, or the extracted text as a String
   */
  def extractText(imagePath: String): Either[LLMError, String] = {
    val ocrPrompt = "Extract and transcribe all text visible in this image. " +
      "Return only the text content, maintaining the original formatting where possible."

    analyzeImage(imagePath, Some(ocrPrompt)).map(_.text.getOrElse(""))
  }

  /**
   * Identifies and describes objects in the image with confidence scores.
   * Uses Claude Vision to detect and locate objects within the image.
   *
   * @param imagePath Path to the image file to analyze
   * @return Either an LLMError if detection fails, or a List of DetectedObject with labels and confidence scores
   */
  def detectObjects(imagePath: String): Either[LLMError, List[DetectedObject]] = {
    val objectDetectionPrompt = "Identify all objects in this image. For each object, provide: " +
      "1. The object name/label " +
      "2. A confidence score (0-1) for how certain you are about the identification " +
      "3. The approximate location description"

    analyzeImage(imagePath, Some(objectDetectionPrompt)).map(_.objects)
  }

  /**
   * Generates descriptive tags for the image content.
   * Creates semantic tags that categorize and describe the image's content, style, and mood.
   *
   * @param imagePath Path to the image file to analyze
   * @return Either an LLMError if tagging fails, or a List of descriptive tags
   */
  def generateTags(imagePath: String): Either[LLMError, List[String]] = {
    val taggingPrompt = "Generate descriptive tags for this image. Include tags for: " +
      "- Main subjects/objects " +
      "- Setting/location " +
      "- Colors and style " +
      "- Mood/atmosphere " +
      "- Any notable features"

    analyzeImage(imagePath, Some(taggingPrompt)).map(_.tags)
  }

  // Private helper methods

  /**
   * Encodes an image file to Base64 format for API transmission.
   *
   * @param imagePath Path to the image file to encode
   * @return Try containing the Base64-encoded string, or failure if encoding fails
   */
  def encodeImageToBase64(imagePath: String): Try[String] =
    Try {
      val imageBytes = Files.readAllBytes(Paths.get(imagePath))
      Base64.getEncoder.encodeToString(imageBytes)
    }

  /**
   * Detects the media type of an image file based on its extension.
   *
   * @param imagePath Path to the image file
   * @return MediaType representing the image format (JPEG, PNG, GIF, or WEBP)
   */
  def detectMediaType(imagePath: String): MediaType =
    MediaType.fromPath(imagePath)

  private def callAnthropicVisionAPI(
    base64Image: String,
    prompt: String,
    mediaType: MediaType
  ): Try[String] =
    Try {
      import sttp.client4._
      import ujson._
      import scala.concurrent.duration._

      // Use type-safe serialization
      val requestBody = AnthropicRequestBody.serialize(
        model = config.model,
        maxTokens = 1000,
        prompt = prompt,
        base64Image = base64Image,
        mediaType = mediaType
      )

      val backend = DefaultSyncBackend(
        options = BackendOptions.Default.connectionTimeout(config.connectTimeoutSeconds.seconds)
      )

      val request = basicRequest
        .post(uri"${config.baseUrl}/v1/messages")
        .header("Content-Type", "application/json")
        .header("x-api-key", config.apiKey)
        .header("anthropic-version", "2023-06-01")
        .body(requestBody)
        .readTimeout(config.requestTimeoutSeconds.seconds)

      val response = request.send(backend)
      backend.close()

      response.code.code match {
        case 200 =>
          response.body match {
            case Right(responseBody) =>
              extractContentFromResponse(responseBody)
            case Left(errorBody) =>
              throw new RuntimeException(s"Unexpected error parsing successful response: $errorBody")
          }
        case statusCode =>
          val errorMessage = response.body match {
            case Left(errorBody) =>
              Try(read(errorBody)).toOption
                .flatMap(js => js.obj.get("error"))
                .map { err =>
                  val message   = err.obj.get("message").flatMap(_.strOpt)
                  val errorType = err.obj.get("type").flatMap(_.strOpt)
                  (message, errorType) match {
                    case (Some(msg), Some(typ)) => s"$typ: $msg"
                    case (Some(msg), None)      => msg
                    case _                      => errorBody
                  }
                }
                .map(d => s"Status $statusCode: $d")
                .getOrElse(s"Status $statusCode: $errorBody")
            case Right(body) => s"Status $statusCode: $body"
          }
          throw new RuntimeException(s"Anthropic API call failed - $errorMessage")
      }
    }

  private def extractContentFromResponse(jsonResponse: String): String = {
    import ujson._

    Try(read(jsonResponse)).toOption
      .flatMap { json =>
        json("content").arr.headOption
          .flatMap(_.obj.get("text"))
          .map(_.str)
      }
      .getOrElse("Could not parse response from Anthropic Vision API")
  }

  private def parseVisionResponse(response: String, metadata: ImageMetadata): ImageAnalysisResult = {
    // This is a simplified parser - in practice, you'd want more sophisticated parsing
    val description = response
    val confidence  = 0.85 // High confidence for Anthropic Vision

    // Extract tags from the response (simple keyword extraction)
    val tags = extractTagsFromText(response)

    // Extract potential objects mentioned in the response
    val objects = extractObjectsFromText(response)

    // Try to extract any text mentioned in the response
    val extractedText = extractTextFromResponse(response)

    ImageAnalysisResult(
      description = description,
      confidence = confidence,
      tags = tags,
      objects = objects,
      emotions = List.empty, // Could be enhanced to detect emotions
      text = extractedText,
      metadata = metadata.copy(processedAt = Instant.now())
    )
  }

  private def extractTagsFromText(text: String): List[String] = {
    // Simple tag extraction based on common keywords
    val commonTags = Set(
      "person",
      "people",
      "man",
      "woman",
      "child",
      "baby",
      "dog",
      "cat",
      "animal",
      "car",
      "building",
      "house",
      "tree",
      "outdoor",
      "indoor",
      "landscape",
      "portrait",
      "nature",
      "city",
      "street",
      "water",
      "sky",
      "mountain",
      "beach",
      "food",
      "restaurant",
      "kitchen",
      "bedroom",
      "living room",
      "technology",
      "computer",
      "phone",
      "book",
      "art",
      "painting"
    )

    val words = text.toLowerCase.split("\\W+").toSet
    commonTags.intersect(words).toList
  }

  private def extractObjectsFromText(text: String): List[DetectedObject] = {
    // Simplified object extraction
    // In practice, you'd want more sophisticated NLP parsing
    val objectKeywords = List("person", "car", "dog", "cat", "building", "tree", "table", "chair")

    objectKeywords
      .filter(keyword => text.toLowerCase.contains(keyword))
      .map(obj =>
        DetectedObject(
          label = obj,
          confidence = 0.75,
          boundingBox = BoundingBox(0, 0, 100, 100) // Placeholder coordinates
        )
      )
  }

  private def extractTextFromResponse(response: String): Option[String] = {
    // Look for patterns that indicate text extraction
    val textPatterns = List(
      "text says \"([^\"]+)\"".r,
      "reads \"([^\"]+)\"".r,
      "contains the text \"([^\"]+)\"".r
    )

    textPatterns
      .flatMap(_.findFirstMatchIn(response.toLowerCase))
      .headOption
      .map(_.group(1))
  }
}
