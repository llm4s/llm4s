package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import upickle.default._
import java.nio.file.Path

import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext, blocking }

object VertexAIClient {

  /*
   * Dedicated ExecutionContext for blocking I/O operations.
   * This ensures we don't block the caller's ExecutionContext (e.g. standard global).
   */
  private[imagegeneration] val blockingEc: ExecutionContext =
    ImageGenerationExecutionContext.bounded("vertex-ai-blocking")
}

/**
 * Google Vertex AI Imagen client for image generation.
 *
 * This client connects to Google Cloud's Vertex AI Imagen API for text-to-image generation.
 * It supports the Imagen 4.0 model and other Imagen variants.
 *
 * @param config Configuration containing project ID, location, model, and authentication settings
 *
 * @example
 * {{{
 * val config = VertexAIConfig(
 *   projectId = "my-gcp-project",
 *   location = "us-central1",
 *   model = "imagen-4.0-generate-001"
 * )
 * val client = new VertexAIClient(config)
 *
 * client.generateImage("a beautiful landscape") match {
 *   case Right(image) => println(s"Generated image: ${image.size}")
 *   case Left(error) => println(s"Error: ${error.message}")
 * }
 * }}}
 */
class VertexAIClient(config: VertexAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private def apiUrl: String =
    s"https://${config.location}-aiplatform.googleapis.com/v1/projects/${config.projectId}/locations/${config.location}/publishers/google/models/${config.model}:predict"

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).flatMap(
      _.headOption.toRight(ValidationError("No image returned from Vertex AI"))
    )

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
    Left(ValidationError("Image editing is not yet supported for Vertex AI provider"))

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] =
    Future {
      blocking {
        generateImage(prompt, options)
      }
    }(VertexAIClient.blockingEc)

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future {
      blocking {
        generateImages(prompt, count, options)
      }
    }(VertexAIClient.blockingEc)

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
    }(VertexAIClient.blockingEc)

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    // Check model accessibility via GET request to model endpoint (lightweight)
    // instead of generating an image which costs money/quota.
    val modelUrl =
      s"https://${config.location}-aiplatform.googleapis.com/v1/projects/${config.projectId}/locations/${config.location}/publishers/google/models/${config.model}"

    val headers = Map(
      "Content-Type"  -> "application/json",
      "Authorization" -> s"Bearer ${config.accessToken}"
    )

    httpClient
      .get(
        url = modelUrl,
        headers = headers,
        timeout = 5000
      )
      .toEither
      .left
      .map(e => ServiceError(s"Health check failed: ${e.getMessage}", 0))
      .flatMap { response =>
        if (response.statusCode == 200) {
          Right(ServiceStatus(HealthStatus.Healthy, "Vertex AI Imagen API is responding"))
        } else if (response.statusCode == 401 || response.statusCode == 403) {
          Right(ServiceStatus(HealthStatus.Degraded, s"Authentication issue: ${response.statusCode}"))
        } else {
          Right(ServiceStatus(HealthStatus.Degraded, s"Service returned status code: ${response.statusCode}"))
        }
      }
  }

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ValidationError("Prompt cannot be empty"))

  private def validateCount(count: Int): Either[ImageGenerationError, Int] =
    Either.cond(count > 0 && count <= 8, count, ValidationError("Count must be between 1 and 8 for Vertex AI"))

  private def buildPayload(prompt: String, count: Int, options: ImageGenerationOptions): ujson.Value = {
    val instance = ujson.Obj("prompt" -> prompt)

    // Add negative prompt if present
    options.negativePrompt.foreach(negPrompt => instance("negativePrompt") = negPrompt)

    val parameters = ujson.Obj(
      "sampleCount" -> count
    )

    // Map size to aspectRatio
    val aspectRatio = options.size match {
      case ImageSize.Square512 | ImageSize.Square1024                => "1:1"
      case ImageSize.Landscape768x512 | ImageSize.Landscape1536x1024 => "16:9"
      case ImageSize.Portrait512x768 | ImageSize.Portrait1024x1536   => "9:16"
    }
    parameters("aspectRatio") = aspectRatio

    // Add seed if present
    options.seed.foreach(seed => parameters("seed") = seed)

    ujson.Obj(
      "instances"  -> ujson.Arr(instance),
      "parameters" -> parameters
    )
  }

  private def makeHttpRequest(payload: ujson.Value): Either[ImageGenerationError, requests.Response] = {
    val headers = Map(
      "Content-Type"  -> "application/json",
      "Authorization" -> s"Bearer ${config.accessToken}"
    )

    logger.debug(s"Making request to: $apiUrl")
    logger.debug(s"Payload: ${write(payload, indent = 2)}")

    httpClient
      .post(
        url = apiUrl,
        headers = headers,
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
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    if (response.statusCode == 401 || response.statusCode == 403) {
      return Left(AuthenticationError("Invalid or missing authentication credentials"))
    }

    if (response.statusCode != 200) {
      val errorMsg = s"API request failed with status ${response.statusCode}: ${response.text()}"
      logger.error(errorMsg)
      return Left(ServiceError(errorMsg, response.statusCode))
    }

    Try {
      val responseJson = read[ujson.Value](response.text())
      val predictions  = responseJson("predictions").arr
      predictions
    }.toEither.left
      .map(e => UnknownError(e))
      .flatMap { predictions =>
        if (predictions.isEmpty) {
          Left(ValidationError("No images returned from the API"))
        } else {
          val generatedImages = predictions.flatMap { prediction =>
            // Vertex AI returns bytesBase64Encoded for each image
            prediction.obj.get("bytesBase64Encoded").map { imageData =>
              GeneratedImage(
                data = imageData.str,
                format = ImageFormat.PNG,
                size = options.size,
                prompt = prompt,
                seed = options.seed
              )
            }
          }.toSeq

          if (generatedImages.isEmpty) {
            Left(ValidationError("No valid images in API response"))
          } else {
            logger.info(s"Successfully generated ${generatedImages.length} image(s)")
            Right(generatedImages)
          }
        }
      }
  }
}
