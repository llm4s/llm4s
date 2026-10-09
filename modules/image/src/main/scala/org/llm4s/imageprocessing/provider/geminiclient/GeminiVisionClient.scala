package org.llm4s.imageprocessing.provider.geminiclient

import org.llm4s.imageprocessing._
import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.llm4s.imageprocessing.provider.LocalImageProcessor
import org.llm4s.error.{ ConfigurationError, LLMError }
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.media.{ ImageMediaType, MediaType }
import ujson.read

import java.nio.file.{ Files, Paths }
import java.time.Instant
import java.util.Base64
import scala.concurrent.duration._
import scala.util.Try
import org.llm4s.util.Redaction

/**
 * Google Gemini Vision client for AI-powered image analysis.
 *
 * Sends multimodal requests (image + text prompt) to the Gemini
 * generateContent REST endpoint and returns structured analysis results.
 *
 * The API key is sent in the `x-goog-api-key` request header, never in the URL, so it cannot
 * reach a log line or an error message through the URL.
 *
 * Failures are returned, not thrown: an HTTP error status is an [[org.llm4s.error.APIError]]
 * carrying the status code; a timeout, connection failure or interruption is the typed
 * [[org.llm4s.error.TimeoutError]], [[org.llm4s.error.NetworkError]] or
 * [[org.llm4s.error.CancelledError]]; a reply that carries no text (a blocked prompt, a
 * candidate stopped for safety or recitation, a body that is not Gemini JSON) is an
 * [[org.llm4s.error.APIError]] too, never a made-up description.
 *
 * @param config [[GeminiVisionConfig]] containing the API key, model, and base URL.
 * @param httpClient the HTTP transport; defaults to the JDK client honouring `config.connectTimeoutSeconds`.
 */
class GeminiVisionClient(config: GeminiVisionConfig, httpClient: Llm4sHttpClient)
    extends org.llm4s.imageprocessing.ImageProcessingClient {

  def this(config: GeminiVisionConfig) =
    this(config, Llm4sHttpClient.create(connectTimeout = config.connectTimeoutSeconds.seconds))

  private val localProcessor = new LocalImageProcessor()

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

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
      _     <- validateConfig()
      basic <- localProcessor.analyzeImage(imagePath, None)
      metadata = basic.metadata
      base64Image <- encodeImageToBase64(imagePath).toEither.left
        .map(e => LLMError.processingFailed("encode", s"Failed to encode image: ${e.getMessage}", Some(e)))
      analysisPrompt = prompt.getOrElse(
        "Analyze this image in detail. Describe what you see, identify any objects, text, or people present. " +
          "Provide tags that categorize the image content."
      )
      mediaType = detectMediaType(imagePath)
      visionResponse <- callGeminiVisionAPI(base64Image, analysisPrompt, mediaType)
    } yield parseVisionResponse(visionResponse, metadata)

  override def preprocessImage(
    imagePath: String,
    operations: List[ImageOperation]
  ): Either[LLMError, ProcessedImage] =
    localProcessor.preprocessImage(imagePath, operations)

  override def convertFormat(
    imagePath: String,
    targetFormat: ImageMediaType
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
  def detectMediaType(imagePath: String): ImageMediaType =
    MediaType.imageFromPath(imagePath).getOrElse(MediaType.Jpeg)

  // ---- private helpers ----

  private def validateConfig(): Either[LLMError, Unit] =
    if (config.apiKey.trim.isEmpty)
      Left(ConfigurationError("Gemini vision API key is blank", List("apiKey")))
    else if (config.model.trim.isEmpty)
      Left(ConfigurationError("Gemini vision model name is blank", List("model")))
    else Right(())

  private def callGeminiVisionAPI(
    base64Image: String,
    prompt: String,
    mediaType: ImageMediaType
  ): Either[LLMError, String] = {
    val requestBody = GeminiRequestBody.serialize(prompt, base64Image, mediaType)
    // The key goes in a header: a query-string key is echoed by URI parse errors and could be
    // captured by proxies and access logs.
    val url     = s"${config.baseUrl}/models/${config.model}:generateContent"
    val headers = Map("Content-Type" -> "application/json", "x-goog-api-key" -> config.apiKey)

    logger.debug(s"[GeminiVisionClient] Sending request to $url")

    httpClient.post(url, headers, requestBody, config.requestTimeoutSeconds.seconds).flatMap { response =>
      if (response.statusCode == 200) extractContentFromResponse(response.body)
      else {
        val errorMessage = describeError(response.statusCode, response.body)
        logger.error(
          "[GeminiVisionClient] HTTP error {}: {}",
          response.statusCode.asInstanceOf[AnyRef],
          Redaction.safeBody(response.body)
        )
        Left(
          LLMError.apiCallFailed(
            "Gemini",
            s"Gemini Vision API call failed - $errorMessage",
            Some(response.statusCode)
          )
        )
      }
    }
  }

  private def describeError(statusCode: Int, responseBody: String): String = {
    val detail = Try(read(responseBody)).toOption
      .flatMap(_.objOpt)
      .flatMap(_.get("error"))
      .flatMap(_.objOpt)
      .flatMap { err =>
        val message = err.get("message").flatMap(_.strOpt)
        val status  = err.get("status").flatMap(_.strOpt)
        (message, status) match {
          case (Some(msg), Some(st)) => Some(s"$st: $msg")
          case (Some(msg), None)     => Some(msg)
          case _                     => None
        }
      }
      .map(Redaction.safeBody(_, 512))
      .getOrElse(Redaction.safeBody(responseBody, 512))
    s"Status $statusCode: $detail"
  }

  /**
   * The text of the first candidate: every text part, in order. A reply with no text is an
   * error that names why (prompt blocked, candidate stopped for safety, malformed body).
   */
  private def extractContentFromResponse(jsonResponse: String): Either[LLMError, String] = {
    def fail(why: String): Either[LLMError, String] =
      Left(LLMError.apiCallFailed("Gemini", s"Gemini Vision API returned no text: $why", Some(200)))

    Try(read(jsonResponse)).toOption.flatMap(_.objOpt) match {
      case None => fail("response body is not a JSON object")
      case Some(root) =>
        val candidate = root.get("candidates").flatMap(_.arrOpt).flatMap(_.headOption).flatMap(_.objOpt)
        val text = candidate
          .flatMap(_.get("content"))
          .flatMap(_.objOpt)
          .flatMap(_.get("parts"))
          .flatMap(_.arrOpt)
          .map(_.toSeq.flatMap(_.objOpt).filterNot(_.get("thought").exists(_.boolOpt.contains(true))))
          .map(_.flatMap(_.get("text").flatMap(_.strOpt)).mkString)
          .filter(_.nonEmpty)
        text match {
          case Some(t) => Right(t)
          case None =>
            val blockReason =
              root.get("promptFeedback").flatMap(_.objOpt).flatMap(_.get("blockReason")).flatMap(_.strOpt)
            val finishReason = candidate.flatMap(_.get("finishReason")).flatMap(_.strOpt)
            (blockReason, finishReason) match {
              case (Some(b), _) => fail(s"prompt blocked (blockReason=$b)")
              case (_, Some(f)) => fail(s"candidate finished with finishReason=$f and no text")
              case _            => fail("no candidates with text in the response")
            }
        }
    }
  }

  private def parseVisionResponse(response: String, metadata: ImageMetadata): ImageAnalysisResult =
    ImageAnalysisResult(
      description = response,
      confidence = 0.85,
      tags = extractTagsFromText(response),
      objects = extractObjectsFromText(response),
      emotions = List.empty,
      text = extractTextFromResponse(response),
      metadata = metadata.copy(processedAt = Instant.now())
    )

  private def extractTagsFromText(text: String): List[String] = {
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
    val objectKeywords = List("person", "car", "dog", "cat", "building", "tree", "table", "chair")
    objectKeywords
      .filter(keyword => text.toLowerCase.contains(keyword))
      .map { obj =>
        DetectedObject(
          label = obj,
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
