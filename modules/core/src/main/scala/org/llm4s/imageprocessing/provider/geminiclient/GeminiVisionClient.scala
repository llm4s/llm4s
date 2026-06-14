// scalafix:off DisableSyntax.NoKeywordTry, DisableSyntax.NoKeywordCatch
package org.llm4s.imageprocessing.provider.geminiclient

import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.llm4s.imageprocessing.provider.LocalImageProcessor
import org.llm4s.error.LLMError
import ujson.read

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.time.{ Duration, Instant }
import java.util.Base64
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Google Gemini Vision client for AI-powered image analysis.
 *
 * Sends multimodal requests (image + text prompt) to the Gemini
 * generateContent REST endpoint and returns structured analysis results.
 *
 * Authentication is done via an `?key=` query parameter as required by the
 * Google Generative Language API.  The full URL (which contains the key) is
 * never logged.
 *
 * @param config [[GeminiVisionConfig]] containing the API key, model, and base URL.
 */
class GeminiVisionClient(config: GeminiVisionConfig) extends org.llm4s.imageprocessing.ImageProcessingClient {

  private val localProcessor = new LocalImageProcessor()

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  private val httpClient = HttpClient
    .newBuilder()
    .connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
    .build()

  /**
   * Analyses an image using the Google Gemini Vision API.
   *
   * @param imagePath Path to the image file to analyse.
   * @param prompt    Optional custom prompt; a default comprehensive prompt is used when absent.
   * @return Either an [[LLMError]] on failure or an [[ImageAnalysisResult]].
   */
  override def analyzeImage(
    imagePath: String,
    prompt: Option[String] = None
  ): Either[LLMError, ImageAnalysisResult] =
    for {
      basic     <- localProcessor.analyzeImage(imagePath, None)
      metadata  = basic.metadata
      base64Image <- encodeImageToBase64(imagePath).toEither.left
        .map(e => LLMError.processingFailed("encode", s"Failed to encode image: ${e.getMessage}", Some(e)))
      analysisPrompt = prompt.getOrElse(
        "Analyze this image in detail. Describe what you see, identify any objects, text, or people present. " +
          "Provide tags that categorize the image content."
      )
      mediaType      = MediaType.fromPath(imagePath)
      visionResponse <- callGeminiVisionAPI(base64Image, analysisPrompt, mediaType).toEither.left
        .map(e => LLMError.apiCallFailed("Gemini", s"Gemini Vision API call failed: ${e.getMessage}"))
    } yield parseVisionResponse(visionResponse, metadata)

  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    localProcessor.preprocessImage(imagePath, operations)

  override def convertFormat(
    imagePath: String,
    targetFormat: ImageFormat
  ): Either[LLMError, ProcessedImage] =
    localProcessor.convertFormat(imagePath, targetFormat)

  override def resizeImage(
    imagePath: String,
    width: Int,
    height: Int,
    maintainAspectRatio: Boolean = true
  ): Either[LLMError, ProcessedImage] =
    localProcessor.resizeImage(imagePath, width, height, maintainAspectRatio)

  // ---- additional Gemini-specific helpers ----

  /**
   * Encodes the image at `imagePath` to a Base64 string.
   *
   * @param imagePath Absolute or relative path to the image file.
   * @return [[scala.util.Try]] wrapping the Base64 string on success.
   */
  def encodeImageToBase64(imagePath: String): Try[String] =
    Try {
      val imageBytes = Files.readAllBytes(Paths.get(imagePath))
      Base64.getEncoder.encodeToString(imageBytes)
    }

  /**
   * Detects the MIME media type of the image from its file extension.
   *
   * @param imagePath Path to the image file.
   * @return [[MediaType]] inferred from the file extension (defaults to JPEG).
   */
  def detectMediaType(imagePath: String): MediaType =
    MediaType.fromPath(imagePath)

  // ---- private helpers ----

  private def callGeminiVisionAPI(
    base64Image: String,
    prompt: String,
    mediaType: MediaType
  ): Try[String] =
    try {
      val requestBody = GeminiRequestBody.serialize(prompt, base64Image, mediaType)
      val url = s"${config.baseUrl}/models/${config.model}:generateContent?key=${config.apiKey}"

      // Do NOT log the URL — it contains the API key as a query parameter
      logger.debug(s"[GeminiVisionClient] Sending request to ${config.baseUrl}/models/${config.model}:generateContent")

      val httpRequest = HttpRequest
        .newBuilder()
        .uri(URI.create(url))
        .header("Content-Type", "application/json")
        .timeout(Duration.ofSeconds(config.requestTimeoutSeconds))
        .POST(HttpRequest.BodyPublishers.ofString(requestBody))
        .build()

      val response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

      response.statusCode() match {
        case 200 =>
          scala.util.Success(extractContentFromResponse(response.body()))
        case statusCode =>
          val responseBody = response.body()
          val errorMessage =
            Try(read(responseBody)).toOption
              .flatMap(_.obj.get("error"))
              .map { err =>
                val message = err.obj.get("message").flatMap(_.strOpt)
                val status  = err.obj.get("status").flatMap(_.strOpt)
                (message, status) match {
                  case (Some(msg), Some(st)) => s"$st: $msg"
                  case (Some(msg), None)     => msg
                  case _                     => org.llm4s.util.Redaction.truncateForLog(responseBody)
                }
              }
              .map(d => s"Status $statusCode: $d")
              .getOrElse(s"Status $statusCode: ${org.llm4s.util.Redaction.truncateForLog(responseBody)}")

          logger.error(
            "[GeminiVisionClient] HTTP error {}: {}",
            statusCode.asInstanceOf[AnyRef],
            org.llm4s.util.Redaction.truncateForLog(responseBody)
          )
          scala.util.Failure(new RuntimeException(s"Gemini API call failed - $errorMessage"))
      }
    } catch {
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        scala.util.Failure(e)
      case NonFatal(e) =>
        scala.util.Failure(e)
    }

  private def extractContentFromResponse(jsonResponse: String): String =
    Try(read(jsonResponse)).toOption
      .flatMap { json =>
        json("candidates").arr.headOption
          .flatMap(_.obj.get("content"))
          .flatMap(_.obj.get("parts"))
          .flatMap(_.arr.headOption)
          .flatMap(_.obj.get("text"))
          .map(_.str)
      }
      .getOrElse("Could not parse response from Gemini Vision API")

  private def parseVisionResponse(response: String, metadata: ImageMetadata): ImageAnalysisResult =
    ImageAnalysisResult(
      description = response,
      confidence  = 0.85,
      tags        = extractTagsFromText(response),
      objects     = extractObjectsFromText(response),
      emotions    = List.empty,
      text        = extractTextFromResponse(response),
      metadata    = metadata.copy(processedAt = Instant.now())
    )

  private def extractTagsFromText(text: String): List[String] = {
    val commonTags = Set(
      "person", "people", "man", "woman", "child", "baby",
      "dog", "cat", "animal", "car", "building", "house", "tree",
      "outdoor", "indoor", "landscape", "portrait", "nature", "city",
      "street", "water", "sky", "mountain", "beach", "food",
      "restaurant", "kitchen", "bedroom", "technology", "computer",
      "phone", "book", "art", "painting"
    )
    val words = text.toLowerCase.split("\\W+").toSet
    commonTags.intersect(words).toList
  }

  private def extractObjectsFromText(text: String): List[DetectedObject] = {
    val objectKeywords = List("person", "car", "dog", "cat", "building", "tree", "table", "chair")
    objectKeywords
      .filter(keyword => text.toLowerCase.contains(keyword))
      .map { obj =>
        DetectedObject(
          label      = obj,
          confidence = 0.75,
          boundingBox = BoundingBox(0, 0, 100, 100)
        )
      }
  }

  private def extractTextFromResponse(response: String): Option[String] = {
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
