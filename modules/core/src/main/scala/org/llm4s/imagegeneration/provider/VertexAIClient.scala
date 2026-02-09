package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import upickle.default._
import java.nio.file.Path

import scala.util.Try
import scala.concurrent.{Future, ExecutionContext}

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
class VertexAIClient(config: VertexAIConfig) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  private def apiUrl: String =
    s"https://${config.location}-aiplatform.googleapis.com/v1/projects/${config.projectId}/locations/${config.location}/publishers/google/models/${config.model}:predict"

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    for {
      _        <- validatePrompt(prompt)
      _        <- validateCount(count)
      payload  <- Right(buildPayload(prompt, count, options))
      response <- makeHttpRequest(payload)
      images   <- parseResponse(response, prompt, options)
    } yield images
  }

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

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    // Vertex AI doesn't have a dedicated health endpoint
    // We'll try to make a minimal API call to check connectivity
    Try {
      val testPayload = ujson.Obj(
        "instances" -> ujson.Arr(ujson.Obj("prompt" -> "test")),
        "parameters" -> ujson.Obj("sampleCount" -> 1)
      )

      val headers = Map(
        "Content-Type" -> "application/json"
      ) ++ config.accessToken.map(token => "Authorization" -> s"Bearer $token").toMap

      val response = requests.post(
        url = apiUrl,
        data = write(testPayload),
        headers = headers,
        readTimeout = 5000,
        connectTimeout = 5000
      )
      response
    }.toEither.left
      .map(e => ServiceError(s"Health check failed: ${e.getMessage}", 0))
      .flatMap { response =>
        if (response.statusCode == 200 || response.statusCode == 400) {
          // 400 is acceptable - means API is reachable but request was invalid (expected for test)
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
    options.negativePrompt.foreach { negPrompt =>
      instance("negativePrompt") = negPrompt
    }

    val parameters = ujson.Obj(
      "sampleCount" -> count
    )

    // Map size to aspectRatio
    val aspectRatio = options.size match {
      case ImageSize.Square512 | ImageSize.Square1024 => "1:1"
      case ImageSize.Landscape768x512 | ImageSize.Landscape1536x1024 => "16:9"
      case ImageSize.Portrait512x768 | ImageSize.Portrait1024x1536 => "9:16"
    }
    parameters("aspectRatio") = aspectRatio

    // Add seed if present
    options.seed.foreach { seed =>
      parameters("seed") = seed
    }

    ujson.Obj(
      "instances" -> ujson.Arr(instance),
      "parameters" -> parameters
    )
  }

  private def makeHttpRequest(payload: ujson.Value): Either[ImageGenerationError, requests.Response] = {
    val headers = Map(
      "Content-Type" -> "application/json"
    ) ++ config.accessToken.map(token => "Authorization" -> s"Bearer $token").toMap

    logger.debug(s"Making request to: $apiUrl")
    logger.debug(s"Payload: ${write(payload, indent = 2)}")

    Try {
      requests.post(
        url = apiUrl,
        data = write(payload),
        headers = headers,
        readTimeout = config.timeout,
        connectTimeout = 10000
      )
    }.toEither.left.map(e => UnknownError(e))
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
      val predictions = responseJson("predictions").arr
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
