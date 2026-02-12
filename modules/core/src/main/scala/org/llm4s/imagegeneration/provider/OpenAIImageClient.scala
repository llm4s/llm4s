package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ MetricsCollector, Outcome }
import org.llm4s.model.ModelRegistry
import org.slf4j.LoggerFactory
import ujson._
import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try

/**
 * OpenAI DALL-E API client for image generation.
 *
 * Supports both DALL-E 2 and DALL-E 3 models.
 */
class OpenAIImageClient(
  config: OpenAIConfig,
  metrics: MetricsCollector = MetricsCollector.noop
) extends ImageGenerationClient {

  private val logger   = LoggerFactory.getLogger(getClass)
  private val apiUrl   = "https://api.openai.com/v1/images/generations"
  private val provider = "openai"

  /**
   * Generate a single image.
   */
  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  /**
   * Generate multiple images.
   */
  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    val startNanos = System.nanoTime()
    val modelName  = config.model

    logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

    val result = for {
      validPrompt <- validatePrompt(prompt)
      validCount  <- validateCount(count)
      response    <- makeApiRequest(validPrompt, validCount, options)
      images      <- parseResponse(response, validPrompt, options)
    } yield images

    val duration = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)

    result match {
      case Right(images) =>
        metrics.observeRequest(provider, modelName, Outcome.Success, duration)

        // Attempt cost estimation (image APIs usually priced per image)
        ModelRegistry.lookup(provider, modelName).foreach { meta =>
          meta.pricing.estimateCost(0, count).foreach(cost => metrics.recordCost(provider, modelName, cost))
        }

        Right(images)

      case Left(error) =>
        metrics.observeRequest(
          provider,
          modelName,
          Outcome.Error(org.llm4s.metrics.ErrorKind.Unknown),
          duration
        )
        Left(error)
    }
  }

  /**
   * Health check using minimal models request.
   */
  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    val response = requests.get(
      "https://api.openai.com/v1/models",
      headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
      readTimeout = 5000,
      connectTimeout = 5000
    )

    if (response.statusCode == 200) {
      Right(ServiceStatus(HealthStatus.Healthy, "OpenAI API is responding"))
    } else if (response.statusCode == 429) {
      Right(ServiceStatus(HealthStatus.Degraded, "Rate limited but operational"))
    } else {
      Right(
        ServiceStatus(
          HealthStatus.Unhealthy,
          s"API returned status ${response.statusCode}"
        )
      )
    }
  }

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    if (prompt.trim.isEmpty)
      Left(ValidationError("Prompt cannot be empty"))
    else if (prompt.length > 4000)
      Left(ValidationError("Prompt cannot exceed 4000 characters"))
    else
      Right(prompt)

  private def validateCount(count: Int): Either[ImageGenerationError, Int] = {
    val maxCount = if (config.model == "dall-e-3") 1 else 10
    if (count < 1 || count > maxCount)
      Left(ValidationError(s"Count must be between 1 and $maxCount for ${config.model}"))
    else
      Right(count)
  }

  private def sizeToApiFormat(size: ImageSize): String =
    size match {
      case ImageSize.Square512        => if (config.model == "dall-e-3") "1024x1024" else "512x512"
      case ImageSize.Square1024       => "1024x1024"
      case ImageSize.Landscape768x512 => if (config.model == "dall-e-3") "1792x1024" else "512x512"
      case ImageSize.Portrait512x768  => if (config.model == "dall-e-3") "1024x1792" else "512x512"
    }

  private def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, requests.Response] = {

    val requestBody = Obj(
      "model"           -> config.model,
      "prompt"          -> prompt,
      "n"               -> count,
      "size"            -> sizeToApiFormat(options.size),
      "response_format" -> "b64_json"
    )

    if (config.model == "dall-e-3") {
      requestBody("quality") = "standard"
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

    if (response.statusCode == 200)
      Right(response)
    else
      handleErrorResponse(response)
  }

  private def handleErrorResponse(
    response: requests.Response
  ): Either[ImageGenerationError, requests.Response] = {

    val errorMessage = Try {
      val json = read(response.text())
      json("error")("message").str
    }.getOrElse(response.text())

    response.statusCode match {
      case 401  => Left(AuthenticationError("Invalid API key"))
      case 429  => Left(RateLimitError("Rate limit exceeded"))
      case 400  => Left(ValidationError(s"Invalid request: $errorMessage"))
      case code => Left(ServiceError(s"API error: $errorMessage", code))
    }
  }

  private def parseResponse(
    response: requests.Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

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
  }
}
