package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import java.time.Instant
import java.nio.file.Path
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext, blocking }

/**
 * Stability AI API client for image generation.
 *
 * This client connects to Stability AI's REST API for text-to-image generation.
 * It supports models like Stable Diffusion XL, Stable Diffusion 3, and other
 * Stability AI models.
 *
 * @param config Configuration containing API key, model selection, and timeout settings
 *
 * @example
 * {{{
 * val config = StabilityAIConfig(
 *   apiKey = "your-stability-api-key",
 *   model = "stable-diffusion-xl-1024-v1-0"
 * )
 * val client = new StabilityAIClient(config)
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
class StabilityAIClient(config: StabilityAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Generate a single image from a text prompt using Stability AI API.
   *
   * @param prompt The text description of the image to generate
   * @param options Optional generation parameters like size, format, etc.
   * @return Either an error or the generated image
   */
  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).flatMap(
      _.headOption.toRight(
        ValidationError("No images were generated")
      )
    )

  /**
   * Generate multiple images from a text prompt using Stability AI API.
   *
   * @param prompt The text description of the images to generate
   * @param count Number of images to generate (1-10)
   * @param options Optional generation parameters
   * @return Either an error or a sequence of generated images
   */
  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

    val result = for {
      validPrompt <- validatePrompt(prompt)
      validCount  <- validateCount(count)
      images      <- makeApiRequest(validPrompt, validCount, options)
    } yield images
    result
  }

  /**
   * Edit an existing image based on a prompt and optional mask.
   *
   * Not currently supported for Stability AI provider.
   */
  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Left(UnsupportedOperation("Image editing is not yet supported for Stability AI provider"))

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
   * Check the health/status of the Stability AI API service.
   *
   * Note: Stability AI doesn't provide a dedicated health endpoint,
   * so we use a minimal user account request as a health check.
   */
  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    val healthUrl = s"${config.baseUrl}/v1/user/account"

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
            message = "Stability AI API is responding"
          )
        } else if (response.statusCode == 429) {
          ServiceStatus(
            status = HealthStatus.Degraded,
            message = "Rate limited but operational"
          )
        } else if (response.statusCode == 401) {
          ServiceStatus(
            status = HealthStatus.Unhealthy,
            message = "Authentication failed"
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
   * Validate the prompt to ensure it meets Stability AI's requirements.
   */
  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    if (prompt.trim.isEmpty) {
      Left(ValidationError("Prompt cannot be empty"))
    } else if (prompt.length > 10000) {
      Left(ValidationError("Prompt cannot exceed 10000 characters"))
    } else {
      Right(prompt)
    }

  /**
   * Validate the count based on Stability AI's limits.
   */
  private def validateCount(count: Int): Either[ImageGenerationError, Int] =
    if (count < 1 || count > 10) {
      Left(ValidationError("Count must be between 1 and 10 for Stability AI"))
    } else {
      Right(count)
    }

  /**
   * Convert ImageSize to Stability AI API format (width and height).
   */
  private def sizeToApiFormat(size: ImageSize): (Int, Int) =
    // All standard ImageSize dimensions are already multiples of 64
    size match {
      case ImageSize.Auto               => (1024, 1024)
      case ImageSize.Square512          => (512, 512)
      case ImageSize.Square1024         => (1024, 1024)
      case ImageSize.Landscape768x512   => (768, 512)
      case ImageSize.Portrait512x768    => (512, 768)
      case ImageSize.Landscape1536x1024 => (1536, 1024)
      case ImageSize.Portrait1024x1536  => (1024, 1536)
      case ImageSize.Custom(w, h)       => (w, h)
    }

  /**
   * Make the actual API request to Stability AI.
   */
  private def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val (width, height) = sizeToApiFormat(options.size)

    // Immutable build - no ArrayBuffer needed
    val textPrompts =
      Seq(ujson.Obj("text" -> prompt)) ++
        options.negativePrompt.map(np => ujson.Obj("text" -> np, "weight" -> ujson.Num(-1.0)))

    val requestFields =
      Seq(
        "text_prompts" -> ujson.Arr.from(textPrompts), // Arr.from avoids the _* spread
        "cfg_scale"    -> ujson.Num(options.guidanceScale),
        "height"       -> ujson.Num(height.toDouble),
        "width"        -> ujson.Num(width.toDouble),
        "samples"      -> ujson.Num(count.toDouble),
        "steps"        -> ujson.Num(options.inferenceSteps.toDouble),
        "sampler"      -> ujson.Str(options.samplerName.getOrElse("AUTO"))
      ) ++ options.seed.map(s => "seed" -> ujson.Num(s.toDouble))
    val requestBody = ujson.Obj.from(requestFields)

    val url = s"${config.baseUrl}/v1/generation/${config.model}/text-to-image"

    httpClient
      .post(
        url,
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json",
          "Accept"        -> "application/json" // Request JSON response with base64 images
        ),
        data = requestBody.toString,
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => UnknownError(e))
      .flatMap { response => // type of response in inferred - no import needed
        if (response.statusCode == 200) {
          parseResponseBody(response.body, prompt, options)
        } else {
          handleErrorStatus(response.statusCode)
        }
      }
  }

  /**
   * Take Int, not Response - no requests.Response in signature
   */
  private def handleErrorStatus(statusCode: Int): Either[ImageGenerationError, Nothing] =
    statusCode match {
      case 401 => Left(AuthenticationError("Invalid API key"))
      case 429 => Left(RateLimitError("Rate limit exceeded"))
      case 400 => Left(ValidationError("Invalid request"))
      case 402 => Left(InsufficientResourcesError("Payment required or insufficient credits"))
      case code =>
        Left(ServiceError(s"API error (status $code)", code))
    }

  // Take String, not Response - no requests.Response in signature
  private def parseResponseBody(
    body: String,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Try {
      val json      = ujson.read(body)
      val artifacts = json("artifacts").arr

      val images = artifacts.map { artifact =>
        val base64Data = artifact("base64").str
        val seed       = artifact.obj.get("seed").map(_.num.toLong)

        GeneratedImage(
          data = base64Data,
          format = options.format,
          size = options.size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = seed.orElse(options.seed),
          filePath = None,
          url = None
        )
      }.toSeq

      logger.info(s"Successfully generated ${images.length} image(s)")
      images
    }.toEither.left.map(e => UnknownError(e))
}
