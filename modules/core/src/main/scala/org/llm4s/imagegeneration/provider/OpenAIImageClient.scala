package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.model.ModelRegistry
import org.llm4s.trace.{ Tracing, TraceEvent }
import org.slf4j.LoggerFactory
import ujson._
import java.time.Instant
import java.nio.file.Path
import scala.concurrent.duration._
import scala.util.Try
import scala.concurrent.{ ExecutionContext, Future }
import requests.Response

/**
 * OpenAI DALL-E API client for image generation.
 *
 * This client integrates with OpenAI's image generation endpoint and supports
 * both DALL-E 2 and DALL-E 3 models, respecting their individual limits (e.g.
 * count restrictions and quality defaults).
 *
 * The client:
 *   - Validates prompt length and image count per model
 *   - Normalizes internal ImageSize values to OpenAI-supported sizes
 *   - Explicitly sets `response_format = "b64_json"` to ensure compatibility
 *     with `parseResponse` and avoid runtime mismatches caused by URL defaults
 *   - Records metrics and optional cost estimation
 *   - Emits structured tracing events when a Tracing instance is provided
 *
 * Size Remapping Note: OpenAI models do not support all arbitrary resolutions.
 * Internal sizes are remapped to the closest supported OpenAI resolution to
 * maintain API compatibility and avoid validation errors.
 *
 * @param config
 *   Configuration containing API key, model selection, base URL and timeout
 *   settings
 * @param httpClient
 *   HTTP client used to execute requests
 * @param metrics
 *   Metrics collector used to record latency and cost
 * @param tracer
 *   Optional tracing implementation for emitting structured events
 *
 * @example
 *   {{{
 * val config = OpenAIConfig(
 *   apiKey = "your-openai-api-key",
 *   model  = "dall-e-2"  // or "dall-e-3"
 * )
 *
 * val client = new OpenAIImageClient(
 *   config,
 *   HttpClient.create()
 * )
 *
 * val options = ImageGenerationOptions(
 *   size   = ImageSize.Square1024,
 *   format = ImageFormat.PNG
 * )
 *
 * client.generateImage("a beautiful landscape", options) match {
 *   case Right(image) =>
 *     println(s"Generated image with size: $${image.size}")
 *   case Left(error) =>
 *     println(s"Image generation failed: $${error.message}")
 * }
 *   }}}
 */

class OpenAIImageClient(
  config: OpenAIConfig,
  httpClient: HttpClient,
  metrics: MetricsCollector = MetricsCollector.noop,
  tracer: Option[Tracing] = None
) extends ImageGenerationClient {

  private val logger   = LoggerFactory.getLogger(getClass)
  private val provider = "openai"

  // ==========================================================
  // PUBLIC API
  // ==========================================================

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).flatMap {
      case head +: _ => Right(head)
      case _         => Left(ServiceError("Empty image response", 500))
    }

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    logger.debug(s"Generating $count image(s) using model ${config.model}")

    val start     = System.nanoTime()
    val modelName = config.model

    val result =
      for {
        validPrompt <- validatePrompt(prompt)
        validCount  <- validateCount(count)
        response    <- makeApiRequest(validPrompt, validCount, options)
        images      <- parseResponse(response, validPrompt, options)
      } yield images

    val duration = FiniteDuration(System.nanoTime() - start, NANOSECONDS)

    result match {
      case Right(images) =>
        metrics.observeRequest(provider, modelName, Outcome.Success, duration)

        estimateImageCost(count, options).foreach { cost =>
          metrics.recordCost(provider, modelName, cost)

          tracer.foreach(
            _.traceEvent(
              TraceEvent.CostRecorded(
                costUsd = cost,
                model = modelName,
                operation = "image_generation",
                tokenCount = count,
                costType = "image"
              )
            )
          )
        }

        Right(images)

      case Left(error) =>
        metrics.observeRequest(
          provider,
          modelName,
          Outcome.Error(mapErrorKind(error)),
          duration
        )
        Left(error)
    }
  }

  // ==========================================================
  // EDIT SUPPORT (FIXED)
  // ==========================================================

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    import requests.MultiItem

    val resolvedSize =
      options.size.getOrElse(ImageSize.Square512)

    val parts = scala.collection.mutable.ArrayBuffer[MultiItem](
      MultiItem("image", imagePath.toFile),
      MultiItem("prompt", prompt),
      MultiItem("model", config.model),
      MultiItem("n", options.n.toString),
      MultiItem(
        "response_format",
        options.responseFormat.getOrElse("b64_json").toString
      ),
      MultiItem("size", sizeToApiFormat(resolvedSize))
    )

    maskPath.foreach(m => parts += MultiItem("mask", m.toFile))

    options.quality.foreach(q => parts += MultiItem("quality", q))

    options.user.foreach(u => parts += MultiItem("user", u))

    // DALL-E 3 default quality safety
    if (config.model == "dall-e-3" && options.quality.isEmpty) {
      parts += MultiItem("quality", "standard")
    }

    val multipart = requests.MultiPart(parts.toSeq: _*)

    httpClient
      .postMultipart(
        s"${config.baseUrl}/images/edits",
        headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}"
        ),
        data = multipart,
        timeout = config.timeout
      )
      .toEither
      .left
      .map(UnknownError.apply)
      .flatMap { response =>
        if (response.statusCode == 200)
          parseResponse(
            response,
            prompt,
            ImageGenerationOptions(
              size = resolvedSize,
              responseFormat = options.responseFormat
            )
          )
        else
          handleErrorResponse(response)
      }
  }

  // ==========================================================
  // HEALTH (FIXED)
  // ==========================================================

  override def health(): Either[ImageGenerationError, ServiceStatus] =
    httpClient
      .get(
        s"${config.baseUrl.stripSuffix("/v1")}/v1/models",
        headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
        timeout = 5000
      )
      .toEither match {

      case Right(response) =>
        response.statusCode match {
          case 200 =>
            Right(ServiceStatus(HealthStatus.Healthy, "OpenAI API reachable"))

          case 401 =>
            Right(ServiceStatus(HealthStatus.Degraded, "Authentication failed"))

          case 429 =>
            Right(ServiceStatus(HealthStatus.Degraded, "Rate limited"))

          case code =>
            Right(ServiceStatus(HealthStatus.Unhealthy, s"HTTP $code"))
        }

      case Left(_) =>
        // Network failure / connection issue
        Right(ServiceStatus(HealthStatus.Unhealthy, "Service unreachable"))
    }

  // ==========================================================
  // VALIDATION
  // ==========================================================

  private def validatePrompt(
    prompt: String
  ): Either[ImageGenerationError, String] = {

    val maxChars =
      if (config.model.startsWith("gpt-image")) 32000 else 4000

    if (prompt.trim.isEmpty)
      Left(ValidationError("Prompt cannot be empty"))
    else if (prompt.length > maxChars)
      Left(ValidationError(s"Prompt cannot exceed $maxChars characters"))
    else
      Right(prompt)
  }

  private def validateCount(count: Int): Either[ImageGenerationError, Int] = {

    val max = if (config.model == "dall-e-3") 1 else 10

    if (count < 1 || count > max)
      Left(ValidationError(s"Count must be between 1 and $max"))
    else
      Right(count)
  }

  // ==========================================================
  // SIZE MAPPING (STRICT SPEC MATCH)
  // ==========================================================

  protected def sizeToApiFormat(size: ImageSize): String =
    size match {
      case ImageSize.Square512          => "1024x1024"
      case ImageSize.Square1024         => "1024x1024"
      case ImageSize.Landscape768x512   => "1536x1024"
      case ImageSize.Portrait512x768    => "1024x1536"
      case ImageSize.Landscape1536x1024 => "1792x1024"
      case ImageSize.Portrait1024x1536  => "1024x1792"
    }

  // ==========================================================
  // HTTP
  // ==========================================================

  protected def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Response] = {

    val requestBody = Obj(
      "model"  -> config.model,
      "prompt" -> prompt,
      "n"      -> count,
      "size"   -> sizeToApiFormat(options.size),
      "response_format" -> ujson.Str(
        options.responseFormat.getOrElse("b64_json").toString
      )
    )

    // Optional parameters
    options.quality.foreach(q => requestBody("quality") = q)
    options.style.foreach(s => requestBody("style") = s)
    options.user.foreach(u => requestBody("user") = u)

    // Backward compatibility default for DALL-E-3
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
      .map(UnknownError.apply)
      .flatMap { response =>
        if (response.statusCode == 200) Right(response)
        else handleErrorResponse(response)
      }
  }

  private def handleErrorResponse(
    response: Response
  ): Either[ImageGenerationError, Nothing] = {

    val msg =
      Try(read(response.text())("error")("message").str)
        .getOrElse(response.text())

    response.statusCode match {
      case 401 => Left(AuthenticationError(msg))
      case 429 => Left(RateLimitError(msg))
      case 400 => Left(ValidationError(msg))
      case c   => Left(ServiceError(msg, c))
    }
  }

  // ==========================================================
  // RESPONSE PARSING
  // ==========================================================

  protected def parseResponse(
    response: Response,
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Try {

      val body = response.text()

      if (body.trim.isEmpty)
        throw new RuntimeException("Empty response body")

      val json = read(body)

      val dataArray =
        json.obj.get("data") match {
          case Some(arr) => arr.arr
          case None =>
            throw new RuntimeException("Missing 'data' field in response")
        }

      dataArray.map { obj =>
        val base64Opt = obj.obj.get("b64_json").map(_.str)
        val urlOpt    = obj.obj.get("url").map(_.str)

        GeneratedImage(
          data = base64Opt.getOrElse(""),
          format = options.format,
          size = options.size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = options.seed,
          filePath = None,
          url = urlOpt
        )
      }.toSeq

    }.toEither.left.map(UnknownError.apply)

  // ==========================================================
  // COST
  // ==========================================================

  protected def estimateImageCost(
    count: Int,
    options: ImageGenerationOptions
  ): Option[Double] = {

    val pixels =
      options.size.width * options.size.height * count

    ModelRegistry
      .lookup(provider, config.model)
      .toOption
      .flatMap(_.pricing.estimateImageCost(count, Some(pixels)))
  }

  // ==========================================================
  // ERROR KIND MAPPING
  // ==========================================================

  private def mapErrorKind(
    error: ImageGenerationError
  ): ErrorKind =
    error match {
      case AuthenticationError(_)        => ErrorKind.Authentication
      case RateLimitError(_)             => ErrorKind.RateLimit
      case ValidationError(_)            => ErrorKind.Validation
      case InvalidPromptError(_)         => ErrorKind.Validation
      case ServiceError(_, 400)          => ErrorKind.Validation
      case ServiceError(_, _)            => ErrorKind.Unknown
      case InsufficientResourcesError(_) => ErrorKind.Unknown
      case UnknownError(_)               => ErrorKind.Unknown
      case UnsupportedOperation(_)       => ErrorKind.Unknown
    }

  // ==========================================================
  // ASYNC
  // ==========================================================

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions
  )(implicit
    ec: ExecutionContext
  ): Future[Either[ImageGenerationError, GeneratedImage]] =
    Future.successful(generateImage(prompt, options))

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  )(implicit
    ec: ExecutionContext
  ): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future.successful(generateImages(prompt, count, options))

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit
    ec: ExecutionContext
  ): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] =
    Future.successful(editImage(imagePath, prompt, maskPath, options))
}
