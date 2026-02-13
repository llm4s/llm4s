package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.model.ModelRegistry
import org.llm4s.trace.Tracing
import org.slf4j.LoggerFactory
import ujson._
import java.time.Instant
import scala.concurrent.duration._
import scala.util.Try

/**
 * OpenAI Image Generation client.
 */
class OpenAIImageClient(
  config: OpenAIConfig,
  metrics: MetricsCollector = MetricsCollector.noop,
  tracer: Option[Tracing] = None
) extends ImageGenerationClient {

  private val logger   = LoggerFactory.getLogger(getClass)
  private val apiUrl   = "https://api.openai.com/v1/images/generations"
  private val provider = "openai"

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).flatMap {
      case head +: _ => Right(head)
      case _ =>
        Left(
          ServiceError(
            message = "Image generation succeeded but returned empty result",
            code = 500
          )
        )
    }

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

        estimateImageCost(count, options).foreach { cost =>
          metrics.recordCost(provider, modelName, cost)

          tracer.foreach(
            _.traceCost(
              costUsd = cost,
              model = modelName,
              operation = "image_generation",
              tokenCount = count,
              costType = "image"
            )
          )
        }

        Right(images)

      case Left(error) =>
        val errorKind = mapErrorKind(error)

        metrics.observeRequest(
          provider,
          modelName,
          Outcome.Error(errorKind),
          duration
        )

        Left(error)
    }
  }

  /**
   * Centralized cost estimation (overrideable in tests).
   */
  protected def estimateImageCost(
    count: Int,
    options: ImageGenerationOptions
  ): Option[Double] = {

    val apiSize = sizeToApiFormat(options.size)

    val pixelCountPerImage: Option[Int] =
      apiSize.split("x").toList match {
        case width :: height :: Nil =>
          for {
            w <- Try(width.toInt).toOption
            h <- Try(height.toInt).toOption
          } yield w * h
        case _ => None
      }

    val totalPixelCount = pixelCountPerImage.map(_ * count)

    ModelRegistry
      .lookup(provider, config.model)
      .toOption
      .flatMap(_.pricing.estimateImageCost(count, totalPixelCount))
  }

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    val response = requests.get(
      "https://api.openai.com/v1/models",
      headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
      readTimeout = 5000,
      connectTimeout = 5000
    )

    if (response.statusCode == 200)
      Right(ServiceStatus(HealthStatus.Healthy, "OpenAI API is responding"))
    else if (response.statusCode == 429)
      Right(ServiceStatus(HealthStatus.Degraded, "Rate limited but operational"))
    else
      Right(
        ServiceStatus(
          HealthStatus.Unhealthy,
          s"API returned status ${response.statusCode}"
        )
      )
  }

  private def mapErrorKind(error: ImageGenerationError): ErrorKind =
    error match {
      case AuthenticationError(_)        => ErrorKind.Authentication
      case RateLimitError(_)             => ErrorKind.RateLimit
      case ValidationError(_)            => ErrorKind.Validation
      case InvalidPromptError(_)         => ErrorKind.Validation
      case ServiceError(_, _)            => ErrorKind.Unknown
      case InsufficientResourcesError(_) => ErrorKind.Unknown
      case UnknownError(_)               => ErrorKind.Unknown
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
      Left(
        ValidationError(
          s"Count must be between 1 and $maxCount for ${config.model}"
        )
      )
    else
      Right(count)
  }

  /**
   * Only use sizes supported by current OpenAI Images API.
   */
  private def sizeToApiFormat(size: ImageSize): String =
    size match {
      case ImageSize.Square512        => "1024x1024"
      case ImageSize.Square1024       => "1024x1024"
      case ImageSize.Landscape768x512 => "1536x1024"
      case ImageSize.Portrait512x768  => "1024x1536"
    }

  protected def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, requests.Response] = {

    val requestBody = Obj(
      "model"  -> config.model,
      "prompt" -> prompt,
      "n"      -> count,
      "size"   -> sizeToApiFormat(options.size)
    )

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

  protected def parseResponse(
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
