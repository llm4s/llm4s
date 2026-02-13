package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import upickle.default._
import java.nio.file.Path
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext }

/**
 * Fal AI client for image generation.
 *
 * This client connects to Fal AI's REST API for text-to-image generation
 * using Flux or SDXL models.
 *
 * @param config Configuration containing API key, model, and timeout settings
 *
 * @example
 * {{{
 * val config = FalAIConfig(
 *   apiKey = "your-fal-api-key",
 *   model = "fal-ai/flux/dev"
 * )
 * val client = new FalAIClient(config)
 *
 * client.generateImage("a beautiful landscape") match {
 *   case Right(image) => println(s"Generated image: ${image.size}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 * }}}
 */
class FalAIClient(config: FalAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private def apiUrl: String =
    s"https://fal.run/${config.model}"

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).flatMap(_.headOption.toRight(ValidationError("No image returned from Fal AI")))

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    for {
      _        <- validatePrompt(prompt)
      _        <- validateCount(count)
      payload  <- Right(buildPayload(prompt, count, options))
      response <- makeHttpRequest(payload)
      images   <- parseResponse(response, prompt, options)
    } yield images

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Left(ValidationError("Image editing is not yet supported for Fal AI provider"))

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
      Right(ServiceStatus(HealthStatus.Degraded, "Fal AI API key not configured"))
    } else {
      // Basic connectivity check
      // Fal doesn't have a standard health endpoint, so we rely on configuration check
      // or we could try a minimal request if needed.
      Right(ServiceStatus(HealthStatus.Healthy, "Fal AI API key configured"))
    }

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ValidationError("Prompt cannot be empty"))

  private def validateCount(count: Int): Either[ImageGenerationError, Int] =
    Either.cond(count > 0 && count <= 4, count, ValidationError("Count must be between 1 and 4 for Fal AI"))

  private def buildPayload(prompt: String, count: Int, options: ImageGenerationOptions): ujson.Value = {
    val imageSize = options.size match {
      case ImageSize.Square512 | ImageSize.Square1024                => "square_hd"
      case ImageSize.Landscape768x512 | ImageSize.Landscape1536x1024 => "landscape_16_9"
      case ImageSize.Portrait512x768 | ImageSize.Portrait1024x1536   => "portrait_16_9"
    }

    val payload = ujson.Obj(
      "prompt"     -> prompt,
      "image_size" -> imageSize,
      "num_images" -> count
    )

    options.negativePrompt.foreach(np => payload("negative_prompt") = np)

    options.seed.foreach { seed =>
      if (seed > Int.MaxValue || seed < Int.MinValue)
        logger.warn(s"Seed value $seed exceeds Int range, using Long value directly")
      payload("seed") = seed
    }

    payload
  }

  private def makeHttpRequest(payload: ujson.Value): Either[ImageGenerationError, requests.Response] = {
    logger.debug(s"Making request to: $apiUrl")
    logger.debug(s"Payload: ${write(payload, indent = 2)}")

    httpClient
      .post(
        url = apiUrl,
        headers = Map(
          "Authorization" -> s"Key ${config.apiKey}",
          "Content-Type"  -> "application/json"
        ),
        data = write(payload),
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => UnknownError(e))
  }

  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    if (response.statusCode == 401 || response.statusCode == 403) {
      Left(AuthenticationError("Invalid or missing Fal AI API key"))
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
          // Fal AI returns images in "images" array with "url" field
          responseJson.obj.get("images") match {
            case Some(imagesArr) =>
              val generatedImages = imagesArr.arr.flatMap { imageObj =>
                imageObj.obj.get("url").map(_.str).filter(_.nonEmpty) match {
                  case Some(imageUrl) =>
                    logger.info(s"Generated image URL: $imageUrl")
                    Some(
                      GeneratedImage(
                        data = None, // No base64 data available
                        format = ImageFormat.PNG,
                        size = options.size,
                        prompt = prompt,
                        seed = options.seed,
                        url = Some(imageUrl)
                      )
                    )
                  case None =>
                    logger.warn("Skipping image with missing or blank URL")
                    None
                }
              }.toSeq
              if (generatedImages.isEmpty) {
                Left(ValidationError("No images returned from the API"))
              } else {
                Right(generatedImages)
              }
            case None =>
              Left(ValidationError("No 'images' field in API response"))
          }
        }
    }
}
