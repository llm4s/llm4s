package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import upickle.default._
import java.nio.file.Path
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Stability AI Direct API client for image generation.
 *
 * This client connects to Stability AI's REST API for text-to-image generation
 * using Stable Diffusion 3.5 models (ultra or core endpoints).
 *
 * @param config Configuration containing API key, model, and timeout settings
 *
 * @example
 * {{{
 * val config = StabilityAIConfig(
 *   apiKey = "your-stability-api-key",
 *   model = "ultra"
 * )
 * val client = new StabilityAIClient(config)
 *
 * client.generateImage("a beautiful landscape") match {
 *   case Right(image) => println(s"Generated image: ${image.size}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 * }}}
 */
class StabilityAIClient(config: StabilityAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private def apiUrl: String =
    s"https://api.stability.ai/v2beta/stable-image/generate/${config.model}"

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    for {
      _      <- validatePrompt(prompt)
      _      <- validateCount(count)
      images <- generateMultipleImages(prompt, count, options)
    } yield images

  private def generateMultipleImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    // Stability AI generates one image per request, so loop for multiple
    val results = (1 to count).map(_ => generateSingleImage(prompt, options))

    val errors = results.collect { case Left(e) => e }
    if (errors.nonEmpty) {
      Left(errors.head)
    } else {
      Right(results.collect { case Right(img) => img })
    }
  }

  private def generateSingleImage(
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, GeneratedImage] = {
    val aspectRatio = options.size match {
      case ImageSize.Square512 | ImageSize.Square1024                => "1:1"
      case ImageSize.Landscape768x512 | ImageSize.Landscape1536x1024 => "16:9"
      case ImageSize.Portrait512x768 | ImageSize.Portrait1024x1536   => "9:16"
    }

    logger.debug(s"Making request to: $apiUrl")
    logger.debug(s"Prompt: $prompt, Aspect Ratio: $aspectRatio")

    val baseItems = Seq(
      requests.MultiItem("prompt", prompt),
      requests.MultiItem("aspect_ratio", aspectRatio),
      requests.MultiItem("output_format", "png")
    )

    val allItems = options.negativePrompt match {
      case Some(np) => baseItems :+ requests.MultiItem("negative_prompt", np)
      case None     => baseItems
    }

    val formData = requests.MultiPart(allItems: _*)

    httpClient
      .postMultipart(
        url = apiUrl,
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Accept"        -> "application/json"
        ),
        data = formData,
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => UnknownError(e))
      .flatMap(parseResponse(_, prompt, options))
  }

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Left(ValidationError("Image editing is not yet supported for Stability AI provider"))

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] =
    Future {
      generateImage(prompt, options)
    }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      generateImages(prompt, count, options)
    }

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      editImage(imagePath, prompt, maskPath, options)
    }

  override def health(): Either[ImageGenerationError, ServiceStatus] =
    // Check if API key is provided
    if (config.apiKey.isEmpty) {
      Right(ServiceStatus(HealthStatus.Degraded, "Stability AI API key not configured"))
    } else {
      Right(ServiceStatus(HealthStatus.Healthy, "Stability AI API key configured"))
    }

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ValidationError("Prompt cannot be empty"))

  private def validateCount(count: Int): Either[ImageGenerationError, Int] =
    Either.cond(count > 0 && count <= 10, count, ValidationError("Count must be between 1 and 10 for Stability AI"))

  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, GeneratedImage] = {
    if (response.statusCode == 401 || response.statusCode == 403) {
      Left(AuthenticationError("Invalid or missing Stability AI API key"))
    } else if (response.statusCode != 200) {
      val errorMsg = s"API request failed with status ${response.statusCode}: ${response.text()}"
      logger.error(errorMsg)
      Left(ServiceError(errorMsg, response.statusCode))
    } else {

    Try {
      val responseJson = read[ujson.Value](response.text())
      responseJson
    }.toEither.left
      .map(e => UnknownError(e))
      .flatMap { responseJson =>
        // Response contains base64 image data
        responseJson.obj
          .get("image")
          .orElse(responseJson.obj.get("artifacts").flatMap(_.arr.headOption.flatMap(_.obj.get("base64")))) match {
          case Some(imageData) =>
            val data = imageData.str
            logger.info("Successfully generated 1 image")
            Right(
              GeneratedImage(
                data = data,
                format = ImageFormat.PNG,
                size = options.size,
                prompt = prompt,
                seed = options.seed
              )
            )
          case None =>
            Left(ValidationError("No image data in API response"))
        }
      }
    }
  }
}
