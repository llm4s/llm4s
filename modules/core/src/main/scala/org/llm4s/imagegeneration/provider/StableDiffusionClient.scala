package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.http.HttpResponse
import org.slf4j.LoggerFactory
import upickle.default._
import java.nio.file.Path
import java.util.Base64
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext, blocking }

/**
 * Represents the JSON payload for the Stable Diffusion WebUI API's text-to-image endpoint.
 * This case class ensures type-safe construction of the request body.
 */
case class StableDiffusionPayload(
  prompt: String,
  negative_prompt: String,
  width: Int,
  height: Int,
  steps: Int,
  cfg_scale: Double,
  batch_size: Int,
  n_iter: Int,
  seed: Long,
  sampler_name: String = "Euler a" // Default sampler
)

object StableDiffusionPayload {
  implicit val writer: Writer[StableDiffusionPayload] = macroW
}

case class StableDiffusionImg2ImgPayload(
  init_images: Seq[String],
  mask: Option[String],
  prompt: String,
  negative_prompt: String,
  width: Int,
  height: Int,
  steps: Int,
  cfg_scale: Double,
  denoising_strength: Double,
  batch_size: Int,
  n_iter: Int,
  seed: Long,
  sampler_name: String = "Euler a"
)

object StableDiffusionImg2ImgPayload {
  implicit val writer: Writer[StableDiffusionImg2ImgPayload] = macroW
}

/**
 * Stable Diffusion WebUI API client for image generation.
 *
 * This client connects to a locally hosted or remote Stable Diffusion WebUI instance
 * through its REST API. It supports all the standard text-to-image generation features
 * including custom sampling, guidance scale, negative prompts, and more.
 *
 * @param config Configuration containing base URL, API key, and timeout settings
 *
 * @example
 * {{{
 * val config = StableDiffusionConfig(
 *   baseUrl = "http://localhost:7860",
 *   apiKey = Some("your-api-key") // optional
 * )
 * val client = new StableDiffusionClient(config)
 *
 * val options = ImageGenerationOptions(
 *   size = ImageSize.Square512,
 *   guidanceScale = 7.5,
 *   negativePrompt = Some("blurry, low quality")
 * )
 *
 * client.generateImage("a beautiful landscape", options) match {
 *   case Right(image) => println(s"Generated image: $${image.size}")
 *   case Left(error) => println(s"Error: $${error.message}")
 * }
 * }}}
 */
class StableDiffusionClient(config: StableDiffusionConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).flatMap(
      _.headOption.toRight(ValidationError("No images returned from Stable Diffusion"))
    )

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    for {
      _        <- Right(logger.info(s"Generating $count image(s) with prompt: $prompt"))
      payload  <- Right(buildPayload(prompt, count, options))
      response <- makeHttpRequest(payload)
      result   <- parseResponse(response, prompt, options)
    } yield result

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] =
    Future {
      blocking {
        generateImage(prompt, options)
      }
    }

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

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val providerOptions = options.providerOptions match {
      case None                                               => Right(ProviderImageEditOptions.StableDiffusion())
      case Some(sd: ProviderImageEditOptions.StableDiffusion) => Right(sd)
      case Some(_) =>
        Left(ValidationError("Unsupported provider-specific edit options for Stable Diffusion image client"))
    }

    def validateDenoisingStrength(value: Option[Double]): Either[ImageGenerationError, Double] = {
      val resolved = value.getOrElse(0.75)
      Either.cond(
        resolved >= 0.0 && resolved <= 1.0,
        resolved,
        ValidationError(s"denoisingStrength must be between 0.0 and 1.0, got: $resolved")
      )
    }

    for {
      opts              <- providerOptions
      denoisingStrength <- validateDenoisingStrength(opts.denoisingStrength)
      sourceImage       <- ImageEditValidationUtils.readImageFile(imagePath, "source image")
      sourceSize        <- ImageEditValidationUtils.readImageSize(imagePath, "source image")
      _                 <- ImageEditValidationUtils.validateMaskDimensions(sourceSize, maskPath)
      img               <- Right(Base64.getEncoder.encodeToString(sourceImage))
      mask <- maskPath match {
        case Some(path) =>
          ImageEditValidationUtils
            .readImageFile(path, "mask image")
            .map(bytes => Some(Base64.getEncoder.encodeToString(bytes)))
        case None => Right(None)
      }
      // Convert ImageEditOptions to ImageGenerationOptions for response parsing
      genOptions = ImageGenerationOptions(
        size = options.size.getOrElse(sourceSize),
        format = ImageFormat.PNG
      )
      payload = StableDiffusionImg2ImgPayload(
        init_images = Seq(img),
        mask = mask,
        prompt = prompt,
        negative_prompt = genOptions.negativePrompt.getOrElse(""),
        width = genOptions.size.width,
        height = genOptions.size.height,
        steps = genOptions.inferenceSteps,
        cfg_scale = genOptions.guidanceScale,
        denoising_strength = denoisingStrength,
        batch_size = options.n,
        n_iter = 1,
        seed = genOptions.seed.getOrElse(-1L),
        sampler_name = genOptions.samplerName.getOrElse("Euler a")
      )
      response <- makeImg2ImgRequest(payload)
      result   <- parseResponse(response, prompt, genOptions)
    } yield result
  }

  private def makeImg2ImgRequest(
    payload: StableDiffusionImg2ImgPayload
  ): Either[ImageGenerationError, HttpResponse] = {
    val url = s"${config.baseUrl}/sdapi/v1/img2img"
    val headers = Map(
      "Content-Type" -> "application/json"
    ) ++ config.apiKey.map(key => "Authorization" -> s"Bearer $key").toMap

    logger.debug(s"Making img2img request to: $url")
    // logger.debug(s"Payload: ${write(payload, indent = 2)}") // Too large to log with base64 images

    httpClient
      .post(
        url = url,
        headers = headers,
        data = write(payload),
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => UnknownError(e))
  }

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    val url = s"${config.baseUrl}/sdapi/v1/options"
    httpClient
      .get(url, Map.empty, 5000)
      .toEither
      .left
      .map(e => ServiceError(s"Health check failed: ${e.getMessage}", 0))
      .map { response =>
        if (response.statusCode == 200)
          ServiceStatus(HealthStatus.Healthy, "Stable Diffusion service is responding")
        else ServiceStatus(HealthStatus.Degraded, s"Service returned status code: ${response.statusCode}")
      }
  }

  private def buildPayload(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): ujson.Value = {
    val payload = StableDiffusionPayload(
      prompt = prompt,
      negative_prompt = options.negativePrompt.getOrElse(""),
      width = options.size.width,
      height = options.size.height,
      steps = options.inferenceSteps,
      cfg_scale = options.guidanceScale,
      batch_size = count,
      n_iter = 1,
      seed = options.seed.getOrElse(-1L),
      sampler_name = options.samplerName.getOrElse("Euler a")
    )
    writeJs(payload)
  }

  private def makeHttpRequest(payload: ujson.Value): Either[ImageGenerationError, HttpResponse] = {
    val url = s"${config.baseUrl}/sdapi/v1/txt2img"
    val headers = Map(
      "Content-Type" -> "application/json"
    ) ++ config.apiKey.map(key => "Authorization" -> s"Bearer $key").toMap

    logger.debug(s"Making request to: $url")
    logger.debug(s"Payload: ${write(payload, indent = 2)}")

    httpClient
      .post(
        url = url,
        headers = headers,
        data = write(payload),
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => UnknownError(e))
  }

  private def parseResponse(
    response: HttpResponse,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    response.statusCode match {
      case 200 => // succeed
      case 401 => return Left(AuthenticationError("API request failed with status 401: Unauthorized"))
      case 429 => return Left(RateLimitError("API request failed with status 429: Rate limit"))
      case _ =>
        val errorMsg = s"API request failed with status ${response.statusCode}: ${response.body}"
        logger.error(errorMsg)
        return Left(ServiceError(errorMsg, response.statusCode))
    }

    Try {
      val responseJson = read[ujson.Value](response.body)
      val images       = responseJson("images").arr
      images
    }.toEither.left
      .map(e => UnknownError(e))
      .flatMap { images =>
        if (images.isEmpty) Left(ValidationError("No images returned from the API"))
        else {
          val generatedImages = images.map { imageData =>
            GeneratedImage(
              data = imageData.str,
              format = options.format,
              size = options.size,
              prompt = prompt,
              seed = options.seed
            )
          }.toSeq
          logger.info(s"Successfully generated ${generatedImages.length} image(s)")
          Right(generatedImages)
        }
      }
  }
}
