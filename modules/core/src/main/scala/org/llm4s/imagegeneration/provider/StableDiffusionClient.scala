package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import upickle.default._
import scala.util.Try

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
class StableDiffusionClient(config: StableDiffusionConfig) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)

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
      _       <- Right(logger.info(s"Generating $count image(s) with prompt: $prompt"))
      payload <- Right(buildPayload(prompt, count, options))
      response <- Try(makeHttpRequest(payload)).toEither.left
        .map(e => UnknownError(e))
      result <- parseResponse(response, prompt, options)
    } yield result

  override def health(): Either[ImageGenerationError, ServiceStatus] = Try {
    val url      = s"${config.baseUrl}/sdapi/v1/options"
    val response = requests.get(url, readTimeout = 5000, connectTimeout = 5000)
    response
  }.toEither.left
    .map(e => ServiceError(s"Health check failed: ${e.getMessage}", 0))
    .map { response =>
      if (response.statusCode == 200)
        ServiceStatus(HealthStatus.Healthy, "Stable Diffusion service is responding")
      else ServiceStatus(HealthStatus.Degraded, s"Service returned status code: ${response.statusCode}")
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

  private def makeHttpRequest(payload: ujson.Value): requests.Response = {
    val url = s"${config.baseUrl}/sdapi/v1/txt2img"
    val headers = Map(
      "Content-Type" -> "application/json"
    ) ++ config.apiKey.map(key => "Authorization" -> s"Bearer $key").toMap

    logger.debug(s"Making request to: $url")
    logger.debug(s"Payload: ${write(payload, indent = 2)}")

    requests.post(
      url = url,
      data = write(payload),
      headers = headers,
      readTimeout = config.timeout,
      connectTimeout = 10000
    )
  }

  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    if (response.statusCode != 200) {
      val errorMsg = s"API request failed with status ${response.statusCode}: ${response.text()}"
      logger.error(errorMsg)
      return Left(ServiceError(errorMsg, response.statusCode))
    }

    Try {
      val responseJson = read[ujson.Value](response.text())
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
