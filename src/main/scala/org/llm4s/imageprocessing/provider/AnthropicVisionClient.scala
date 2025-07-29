package org.llm4s.imageprocessing.provider

import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.AnthropicVisionConfig
import org.llm4s.llmconnect.model.LLMError
import java.time.Instant
import java.util.Base64
import java.nio.file.{ Files, Paths }
import scala.util.{ Try, Success, Failure }

/**
 * Anthropic Claude Vision client for AI-powered image analysis.
 * This client provides advanced image understanding capabilities using Claude's vision models.
 */
class AnthropicVisionClient(config: AnthropicVisionConfig) extends org.llm4s.imageprocessing.ImageProcessingClient {

  private val localProcessor = new LocalImageProcessor()

  override def analyzeImage(
    imagePath: String,
    prompt: Option[String] = None
  ): Either[LLMError, ImageAnalysisResult] =
    try {
      // First, get basic metadata using local processor
      val basicAnalysis = localProcessor.analyzeImage(imagePath, None)
      val metadata      = basicAnalysis.map(_.metadata).getOrElse(ImageMetadata(originalPath = Some(imagePath)))

      // Convert image to base64 for API call
      val base64Image = encodeImageToBase64(imagePath) match {
        case Success(encoded) => encoded
        case Failure(exception) =>
          return Left(LLMError.ProcessingError(s"Failed to encode image: ${exception.getMessage}"))
      }

      // Call Anthropic Vision API
      val analysisPrompt = prompt.getOrElse(
        "Analyze this image in detail. Describe what you see, identify any objects, text, or people present. " +
          "Provide tags that categorize the image content."
      )

      val visionResponse = callAnthropicVisionAPI(base64Image, analysisPrompt) match {
        case Success(response) => response
        case Failure(exception) =>
          return Left(LLMError.APIError(s"Anthropic Vision API call failed: ${exception.getMessage}"))
      }

      // Parse the response and extract structured information
      val parsedResult = parseVisionResponse(visionResponse, metadata)
      Right(parsedResult)

    } catch {
      case e: Exception =>
        Left(LLMError.ProcessingError(s"Error analyzing image with Anthropic Vision: ${e.getMessage}"))
    }

  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    // Delegate preprocessing to local processor
    localProcessor.preprocessImage(imagePath, operations)

  override def convertFormat(
    imagePath: String,
    targetFormat: ImageFormat
  ): Either[LLMError, ProcessedImage] =
    // Delegate format conversion to local processor
    localProcessor.convertFormat(imagePath, targetFormat)

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
   * Performs Optical Character Recognition (OCR) on the image.
   */
  def extractText(imagePath: String): Either[LLMError, String] = {
    val ocrPrompt = "Extract and transcribe all text visible in this image. " +
      "Return only the text content, maintaining the original formatting where possible."

    analyzeImage(imagePath, Some(ocrPrompt)).map(_.text.getOrElse(""))
  }

  /**
   * Identifies and describes objects in the image with confidence scores.
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

  private def encodeImageToBase64(imagePath: String): Try[String] =
    Try {
      val imageBytes = Files.readAllBytes(Paths.get(imagePath))
      Base64.getEncoder.encodeToString(imageBytes)
    }

  private def callAnthropicVisionAPI(base64Image: String, prompt: String): Try[String] =
    Try {
      // This is a simplified implementation
      // In a real implementation, you would use an HTTP client to call the Anthropic API
      import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
      import java.net.URI

      val client = HttpClient.newHttpClient()

      val requestBody = s"""{
        "model": "${config.model}",
        "max_tokens": 1000,
        "messages": [
          {
            "role": "user",
            "content": [
              {
                "type": "text",
                "text": "$prompt"
              },
              {
                "type": "image",
                "source": {
                  "type": "base64",
                  "media_type": "image/jpeg",
                  "data": "$base64Image"
                }
              }
            ]
          }
        ]
      }"""

      val request = HttpRequest
        .newBuilder()
        .uri(URI.create(s"${config.baseUrl}/v1/messages"))
        .header("Content-Type", "application/json")
        .header("x-api-key", config.apiKey)
        .header("anthropic-version", "2023-06-01")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

      val response = client.send(request, HttpResponse.BodyHandlers.ofString())

      if (response.statusCode() == 200) {
        // Parse JSON response to extract the content
        // This is simplified - you'd want to use a proper JSON library
        val responseBody = response.body()
        extractContentFromResponse(responseBody)
      } else {
        throw new RuntimeException(s"API call failed with status ${response.statusCode()}: ${response.body()}")
      }
    }

  private def extractContentFromResponse(jsonResponse: String): String = {
    // Simplified JSON parsing for Anthropic response format
    val contentPattern = "\"text\"\\s*:\\s*\"([^\"]+)\"".r
    contentPattern.findFirstMatchIn(jsonResponse) match {
      case Some(m) => m.group(1).replace("\\n", "\n").replace("\\\"", "\"")
      case None    => "Could not parse response from Anthropic Vision API"
    }
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
