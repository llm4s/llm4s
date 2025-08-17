package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import ujson._
import java.time.Instant

/**
 * OpenAI DALL-E API client for image generation.
 *
 * This client connects to OpenAI's DALL-E API for text-to-image generation.
 * It supports both DALL-E 2 and DALL-E 3 models with their respective capabilities
 * and limitations.
 *
 * @param config Configuration containing API key, model selection, and timeout settings
 *
 * @example
 * {{{
 * val config = OpenAIConfig(
 *   apiKey = "your-openai-api-key",
 *   model = "dall-e-2"  // or "dall-e-3"
 * )
 * val client = new OpenAIImageClient(config)
 *
 * val options = ImageGenerationOptions(
 *   size = ImageSize.Square1024,
 *   format = ImageFormat.PNG
 * )
 *
 * client.generateImage("a beautiful landscape", options) match {
 *   case Right(image) => println(s"Generated image: ${image.size}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 * }}}
 */
class OpenAIImageClient(config: OpenAIConfig) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)
  private val apiUrl = "https://api.openai.com/v1/images/generations"

  /**
   * Generate a single image from a text prompt using OpenAI DALL-E API.
   *
   * @param prompt The text description of the image to generate
   * @param options Optional generation parameters like size, format, etc.
   * @return Either an error or the generated image
   */
  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  /**
   * Generate multiple images from a text prompt using OpenAI DALL-E API.
   *
   * Note: DALL-E 3 only supports generating 1 image at a time.
   *
   * @param prompt The text description of the images to generate
   * @param count The number of images to generate (1-10 for DALL-E 2, 1 for DALL-E 3)
   * @param options Optional generation parameters
   * @return Either an error or a sequence of generated images
   */
  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    try {
      logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

      // Validate input
      for {
        validPrompt <- validatePrompt(prompt)
        validCount  <- validateCount(count)
        response    <- makeApiRequest(validPrompt, validCount, options)
        images      <- parseResponse(response, validPrompt, options)
      } yield images

    } catch {
      case e: Exception =>
        logger.error(s"Error generating images: ${e.getMessage}", e)
        Left(UnknownError(e))
    }

  /**
   * Check the health/status of the OpenAI API service.
   *
   * Note: OpenAI doesn't provide a dedicated health endpoint,
   * so we use a minimal models list request as a health check.
   */
  override def health(): Either[ImageGenerationError, ServiceStatus] =
    try {
      val response = requests.get(
        "https://api.openai.com/v1/models",
        headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
        readTimeout = 5000,
        connectTimeout = 5000
      )

      if (response.statusCode == 200) {
        Right(
          ServiceStatus(
            status = HealthStatus.Healthy,
            message = "OpenAI API is responding"
          )
        )
      } else if (response.statusCode == 429) {
        Right(
          ServiceStatus(
            status = HealthStatus.Degraded,
            message = "Rate limited but operational"
          )
        )
      } else {
        Right(
          ServiceStatus(
            status = HealthStatus.Unhealthy,
            message = s"API returned status ${response.statusCode}"
          )
        )
      }
    } catch {
      case e: Exception =>
        logger.error(s"Health check failed: ${e.getMessage}", e)
        Right(
          ServiceStatus(
            status = HealthStatus.Unhealthy,
            message = s"Cannot reach OpenAI API: ${e.getMessage}"
          )
        )
    }

  /**
   * Validate the prompt to ensure it meets OpenAI's requirements.
   */
  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    if (prompt.trim.isEmpty) {
      Left(ValidationError("Prompt cannot be empty"))
    } else if (prompt.length > 4000) {
      Left(ValidationError("Prompt cannot exceed 4000 characters"))
    } else {
      Right(prompt)
    }

  /**
   * Validate the count based on the model being used.
   */
  private def validateCount(count: Int): Either[ImageGenerationError, Int] = {
    val maxCount = if (config.model == "dall-e-3") 1 else 10
    if (count < 1 || count > maxCount) {
      Left(ValidationError(s"Count must be between 1 and $maxCount for ${config.model}"))
    } else {
      Right(count)
    }
  }

  /**
   * Convert ImageSize to DALL-E API format string.
   */
  private def sizeToApiFormat(size: ImageSize): String =
    // Map our generic sizes to DALL-E supported sizes
    size match {
      case ImageSize.Square512        => if (config.model == "dall-e-3") "1024x1024" else "512x512"
      case ImageSize.Square1024       => "1024x1024"
      case ImageSize.Landscape768x512 => if (config.model == "dall-e-3") "1792x1024" else "512x512"
      case ImageSize.Portrait512x768  => if (config.model == "dall-e-3") "1024x1792" else "512x512"
      case _                          => "1024x1024" // Default fallback
    }

  /**
   * Make the actual API request to OpenAI.
   */
  private def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, requests.Response] =
    try {
      val requestBody = Obj(
        "model"           -> config.model,
        "prompt"          -> prompt,
        "n"               -> count,
        "size"            -> sizeToApiFormat(options.size),
        "response_format" -> "b64_json"
      )

      // Add quality parameter for DALL-E 3
      if (config.model == "dall-e-3") {
        requestBody("quality") = "standard" // or "hd" for higher quality
      }

      val response = requests.post(
        apiUrl,
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        ),
        data = requestBody.toString,
        readTimeout = config.timeout,
        connectTimeout = 10000
      )

      if (response.statusCode == 200) {
        Right(response)
      } else {
        handleErrorResponse(response)
      }
    } catch {
      case e: Exception =>
        logger.error(s"API request failed: ${e.getMessage}", e)
        Left(ServiceError(s"Request failed: ${e.getMessage}", 0))
    }

  /**
   * Handle error responses from the API.
   */
  private def handleErrorResponse(response: requests.Response): Either[ImageGenerationError, requests.Response] = {
    val errorMessage =
      try {
        val json = read(response.text())
        json("error")("message").str
      } catch {
        case _: Exception => response.text()
      }

    response.statusCode match {
      case 401  => Left(AuthenticationError("Invalid API key"))
      case 429  => Left(RateLimitError("Rate limit exceeded"))
      case 400  => Left(ValidationError(s"Invalid request: $errorMessage"))
      case code => Left(ServiceError(s"API error: $errorMessage", code))
    }
  }

  /**
   * Parse the API response into GeneratedImage objects.
   */
  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    try {
      val json       = read(response.text())
      val imagesData = json("data").arr

      val images = imagesData.map { imageData =>
        val base64Data = imageData("b64_json").str

        GeneratedImage(
          data = base64Data,
          format = options.format,
          size = options.size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = options.seed,
          filePath = None
        )
      }.toSeq

      logger.info(s"Successfully generated ${images.length} image(s)")
      Right(images)
    } catch {
      case e: Exception =>
        logger.error(s"Failed to parse response: ${e.getMessage}", e)
        Left(ServiceError(s"Failed to parse response: ${e.getMessage}", 500))
    }
}
