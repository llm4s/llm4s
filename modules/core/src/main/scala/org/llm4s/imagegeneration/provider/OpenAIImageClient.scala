package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import ujson._
import java.time.Instant
import java.nio.file.Path
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext, blocking }

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
 *   case Right(image) => println(s"Generated image: $${image.size}")
 *   case Left(error) => println(s"Error: $${error.message}")
 * }
 * }}}
 */
class OpenAIImageClient(config: OpenAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

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
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

    // Validate input
    val result = for {
      validPrompt <- validatePrompt(prompt)
      validCount  <- validateCount(count)
      response    <- makeApiRequest(validPrompt, validCount, options)
      images      <- parseResponse(response, validPrompt, options)
    } yield images
    result
  }

  /**
   * Edit an existing image based on a prompt and optional mask.
   *
   * @param imagePath Path to the image to edit (PNG, < 4MB)
   * @param prompt The text description of the desired edit
   * @param maskPath Optional path to the mask image (PNG, < 4MB)
   * @param options Optional generation parameters
   * @return Either an error or a sequence of generated images
   */
  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    // Validate image format manually as simple check, real validation happens at API
    if (!imagePath.toString.toLowerCase.endsWith(".png")) {
      Left(ValidationError("Image must be a PNG file"))
    } else {
      val editUrl = s"${config.baseUrl}/images/edits"

      val parts = scala.collection.mutable.ListBuffer[requests.MultiItem](
        requests.MultiItem("image", imagePath, filename = imagePath.getFileName.toString),
        requests.MultiItem("prompt", prompt),
        requests.MultiItem("n", options.n.toString),
        requests.MultiItem("response_format", options.responseFormat.getOrElse("b64_json"): String)
      )

      // Always use dall-e-2 for edits as it's the only supported model for this endpoint
      parts += requests.MultiItem("model", "dall-e-2")

      maskPath.foreach(path => parts += requests.MultiItem("mask", path, filename = path.getFileName.toString))
      options.size.foreach(s => parts += requests.MultiItem("size", sizeToApiFormat(s): String))
      options.user.foreach(u => parts += requests.MultiItem("user", u: String))

      val result = httpClient
        .postMultipart(
          editUrl,
          headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
          data = requests.MultiPart(parts.toSeq: _*),
          timeout = config.timeout
        )
        .toEither
        .left
        .map(e => UnknownError(e))

      result.flatMap { response =>
        if (response.statusCode == 200) {
          // reuse parseResponse logic but map ImageEditOptions to ImageGenerationOptions for compatibility
          val genOptions = ImageGenerationOptions(
            size = options.size.getOrElse(ImageSize.Square1024), // API default
            format = ImageFormat.PNG,                            // Default
            responseFormat = options.responseFormat,             // Pass through
          )
          parseResponse(response, prompt, genOptions)
        } else {
          handleErrorResponse(response) match {
            case Left(e) => Left(e)
            case Right(_) =>
              Left(UnknownError(new RuntimeException("Unexpected successful response during error handling")))
          }
        }
      }
    }

  /**
   * Generate an image asynchronously
   */
  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] =
    Future {
      blocking {
        generateImage(prompt, options)
      }
    }

  /**
   * Generate multiple images asynchronously
   */
  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      blocking {
        generateImages(prompt, count, options)
      }
    }

  /**
   * Edit an existing image asynchronously
   */
  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      blocking {
        editImage(imagePath, prompt, maskPath, options)
      }
    }

  /**
   * Check the health/status of the OpenAI API service.
   *
   * Note: OpenAI doesn't provide a dedicated health endpoint,
   * so we use a minimal models list request as a health check.
   */
  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    val healthUrl = s"${config.baseUrl.stripSuffix("/images/generations").stripSuffix("/v1")}/v1/models"

    httpClient
      .get(
        healthUrl,
        headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
        timeout = 5000
      )
      .toEither
      .left
      .map(e => ServiceError(s"Health check failed: ${e.getMessage}", 0))
      .map { response =>
        if (response.statusCode == 200) {
          ServiceStatus(
            status = HealthStatus.Healthy,
            message = "OpenAI API is responding"
          )
        } else if (response.statusCode == 429) {
          ServiceStatus(
            status = HealthStatus.Degraded,
            message = "Rate limited but operational"
          )
        } else {
          ServiceStatus(
            status = HealthStatus.Unhealthy,
            message = s"API returned status ${response.statusCode}"
          )
        }
      }
  }

  /**
   * Validate the prompt to ensure it meets OpenAI's requirements.
   */
  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] = {
    val maxChars = if (config.model.startsWith("gpt-image")) 32000 else 4000
    if (prompt.trim.isEmpty) {
      Left(ValidationError("Prompt cannot be empty"))
    } else if (prompt.length > maxChars) {
      Left(ValidationError(s"Prompt cannot exceed $maxChars characters"))
    } else {
      Right(prompt)
    }
  }

  /**
   * Validate the count based on the model being used.
   */
  private def validateCount(count: Int): Either[ImageGenerationError, Int] = {
    val maxCount = if (config.model.startsWith("gpt-image")) 10 else if (config.model == "dall-e-3") 1 else 10
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
      case ImageSize.Square512          => if (config.model == "dall-e-3") "1024x1024" else "512x512"
      case ImageSize.Square1024         => "1024x1024"
      case ImageSize.Landscape768x512   => if (config.model == "dall-e-3") "1792x1024" else "512x512"
      case ImageSize.Portrait512x768    => if (config.model == "dall-e-3") "1024x1792" else "512x512"
      case ImageSize.Landscape1536x1024 => "1792x1024" // Closest matching for DALL-E 3/GPT
      case ImageSize.Portrait1024x1536  => "1024x1792" // Closest matching for DALL-E 3/GPT
    }

  /**
   * Make the actual API request to OpenAI.
   */
  private def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, requests.Response] = {
    // Deprecation warning
    if (config.model.startsWith("dall-e")) {
      logger.warn(
        s"Model ${config.model} is deprecated and will be removed on May 12, 2026. Please migrate to gpt-image models."
      )
    }

    val requestBody = Obj(
      "model"           -> config.model,
      "prompt"          -> prompt,
      "n"               -> count,
      "size"            -> sizeToApiFormat(options.size),
      "response_format" -> ujson.Str(options.responseFormat.getOrElse("b64_json"))
    )

    // Optional parameters
    options.quality.foreach(q => requestBody("quality") = q)
    options.style.foreach(s => requestBody("style") = s)
    options.user.foreach(u => requestBody("user") = u)

    // Backward compatibility defaults for DALL-E 3 if not specified
    if (config.model == "dall-e-3" && options.quality.isEmpty) {
      requestBody("quality") = "standard"
    }

    val url = s"${config.baseUrl}/images/generations"

    httpClient
      .post(
        url,
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        ),
        data = requestBody.toString,
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => UnknownError(e))
      .flatMap { response =>
        if (response.statusCode == 200) {
          Right(response)
        } else {
          handleErrorResponse(response)
        }
      }
  }

  /**
   * Handle error responses from the API.
   */
  private def handleErrorResponse(response: requests.Response): Either[ImageGenerationError, requests.Response] = {
    val errorMessage = Try {
      val json = read(response.text())
      json("error")("message").str
    }
      .getOrElse(response.text())

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
    Try {
      val json       = read(response.text())
      val imagesData = json("data").arr

      val images = imagesData.map { imageData =>
        val (data, url) = if (imageData.obj.contains("b64_json")) {
          (imageData("b64_json").str, None)
        } else if (imageData.obj.contains("url")) {
          ("", Some(imageData("url").str))
        } else {
          ("", None)
        }

        GeneratedImage(
          data = data,
          format = options.format,
          size = options.size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = options.seed,
          filePath = None,
          url = url
        )
      }.toSeq

      logger.info(s"Successfully generated ${images.length} image(s)")
      images
    }.toEither.left.map(e => UnknownError(e))
}
