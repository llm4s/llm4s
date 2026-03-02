package org.llm4s.imagegeneration.provider

import org.llm4s.http.{ HttpResponse, MultipartPart }
import org.llm4s.imagegeneration._
import org.slf4j.LoggerFactory
import ujson._

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.util.Try

/**
 * OpenAI Images API client for image generation.
 *
 * Supports GPT Image models and legacy DALL-E models.
 */
class OpenAIImageClient(config: OpenAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)
  warnIfDeprecatedModelConfigured()

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options)
      .flatMap(_.headOption.toRight(ValidationError("No images returned from OpenAI image generation endpoint")))

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

    for {
      validPrompt <- validatePrompt(prompt)
      validCount  <- validateCount(count)
      _           <- validateGenerationOptions(options)
      response    <- makeApiRequest(validPrompt, validCount, options)
      images <- parseResponse(
        response = response,
        prompt = validPrompt,
        size = options.size,
        fallbackFormat = options.format,
        requestedOutputFormat = options.outputFormat,
        seed = options.seed
      )
    } yield images
  }

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val validated = for {
      _             <- validatePrompt(prompt)
      _             <- validateCount(options.n)
      openAIOptions <- extractOpenAIEditOptions(options)
      _             <- validateEditResponseFormat(openAIOptions.responseFormat)
      sourceSize    <- ImageEditValidationUtils.readImageSize(imagePath, "source image")
      _             <- ImageEditValidationUtils.validateMaskDimensions(sourceSize, maskPath)
      requestedSize <- resolveEditOutputSize(options.size, sourceSize)
      _             <- validateEditSize(requestedSize)
    } yield (openAIOptions, requestedSize)

    validated.flatMap { case (openAIOptions, requestedSize) =>
      val editUrl = s"${config.baseUrl}/images/edits"
      val parts = scala.collection.mutable.ListBuffer[MultipartPart](
        MultipartPart.FilePart("image", imagePath, imagePath.getFileName.toString),
        MultipartPart.TextField("prompt", prompt),
        MultipartPart.TextField("n", options.n.toString),
        openAIOptions.responseFormat
          .fold(MultipartPart.TextField("response_format", "b64_json"))(rf =>
            MultipartPart.TextField("response_format", rf)
          )
      )

      // OpenAI edit endpoint currently supports DALL-E 2.
      parts += MultipartPart.TextField("model", "dall-e-2")
      maskPath.foreach(path => parts += MultipartPart.FilePart("mask", path, path.getFileName.toString))
      parts += MultipartPart.TextField("size", sizeToApiFormat(requestedSize))
      openAIOptions.user.foreach(u => parts += MultipartPart.TextField("user", u))
      openAIOptions.quality.foreach(q => parts += MultipartPart.TextField("quality", q))
      openAIOptions.style.foreach(s => parts += MultipartPart.TextField("style", s))

      httpClient
        .postMultipart(
          editUrl,
          headers = Map("Authorization" -> s"Bearer ${config.apiKey}"),
          data = parts.toSeq,
          timeout = config.timeout
        )
        .toEither
        .left
        .map(UnknownError.apply)
        .flatMap { response =>
          if (response.statusCode == 200) {
            parseResponse(
              response = response,
              prompt = prompt,
              size = requestedSize,
              fallbackFormat = ImageFormat.PNG,
              requestedOutputFormat = None,
              seed = None
            ).flatMap(images =>
              Either
                .cond(images.nonEmpty, images, ValidationError("No images returned from OpenAI image edit endpoint"))
            )
          } else {
            handleErrorResponse(response)
          }
        }
    }
  }

  private def validateEditResponseFormat(responseFormat: Option[String]): Either[ImageGenerationError, Unit] =
    responseFormat match {
      case None                     => Right(())
      case Some("b64_json" | "url") => Right(())
      case Some(other)              => Left(ValidationError(s"Unsupported response format for edit: $other"))
    }

  private def extractOpenAIEditOptions(
    options: ImageEditOptions
  ): Either[ImageGenerationError, ProviderImageEditOptions.OpenAI] =
    options.providerOptions match {
      case None                                          => Right(ProviderImageEditOptions.OpenAI())
      case Some(openAI: ProviderImageEditOptions.OpenAI) => Right(openAI)
      case Some(_) =>
        Left(ValidationError("Unsupported provider-specific edit options for OpenAI image client"))
    }

  private def resolveEditOutputSize(
    requestedSize: Option[ImageSize],
    sourceSize: ImageSize
  ): Either[ImageGenerationError, ImageSize] =
    Right(requestedSize.getOrElse(sourceSize))

  private def validateEditSize(size: ImageSize): Either[ImageGenerationError, Unit] = {
    val allowedSizes = Set("256x256", "512x512", "1024x1024")
    val requested    = sizeToApiFormat(size)
    Either.cond(
      allowedSizes.contains(requested),
      (),
      ValidationError(s"Unsupported edit size '$requested'. Allowed sizes: ${allowedSizes.toSeq.sorted.mkString(", ")}")
    )
  }

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

  override def health(): Either[ImageGenerationError, ServiceStatus] = {
    val healthUrl = s"${config.baseUrl.stripSuffix("/images/generations").stripSuffix("/v1")}/v1/models"

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
          ServiceStatus(status = HealthStatus.Healthy, message = "OpenAI API is responding")
        } else if (response.statusCode == 429) {
          ServiceStatus(status = HealthStatus.Degraded, message = "Rate limited but operational")
        } else {
          ServiceStatus(status = HealthStatus.Unhealthy, message = s"API returned status ${response.statusCode}")
        }
      }
  }

  private def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    if (prompt.trim.isEmpty) {
      Left(ValidationError("Prompt cannot be empty"))
    } else if (prompt.length > maxPromptLength) {
      Left(ValidationError(s"Prompt cannot exceed $maxPromptLength characters for ${config.model}"))
    } else {
      Right(prompt)
    }

  private def validateCount(count: Int): Either[ImageGenerationError, Int] = {
    val maxCount = if (isDallE3Model) 1 else 10
    if (count < 1 || count > maxCount) {
      Left(ValidationError(s"Count must be between 1 and $maxCount for ${config.model}"))
    } else {
      Right(count)
    }
  }

  private def validateGenerationOptions(options: ImageGenerationOptions): Either[ImageGenerationError, Unit] =
    for {
      _ <- validateResponseFormat(options.responseFormat)
      _ <- validateOutputFormat(options.outputFormat)
      _ <- validateOutputCompression(options.outputCompression)
      _ <- validateModelOptionCompatibility(options)
    } yield ()

  private def validateResponseFormat(responseFormat: Option[String]): Either[ImageGenerationError, Unit] =
    responseFormat match {
      case None                     => Right(())
      case Some("b64_json" | "url") => Right(())
      case Some(unsupported) => Left(ValidationError(s"Unsupported response format for generation: $unsupported"))
    }

  private def validateOutputFormat(outputFormat: Option[String]): Either[ImageGenerationError, Unit] =
    outputFormat match {
      case None                          => Right(())
      case Some("png" | "jpeg" | "webp") => Right(())
      case Some(other)                   => Left(ValidationError(s"Unsupported output format: $other"))
    }

  private def validateOutputCompression(outputCompression: Option[Int]): Either[ImageGenerationError, Unit] =
    outputCompression match {
      case None                                      => Right(())
      case Some(level) if level >= 0 && level <= 100 => Right(())
      case Some(level) => Left(ValidationError(s"Output compression must be between 0 and 100, got: $level"))
    }

  private def validateModelOptionCompatibility(options: ImageGenerationOptions): Either[ImageGenerationError, Unit] =
    if (
      !isGptImageModel && (options.outputFormat.isDefined || options.outputCompression.isDefined || options.background.isDefined)
    ) {
      Left(
        ValidationError(
          s"outputFormat/outputCompression/background are only supported for GPT Image models; got model ${config.model}"
        )
      )
    } else {
      Right(())
    }

  private def isDallE2Model: Boolean   = config.model == "dall-e-2"
  private def isDallE3Model: Boolean   = config.model == "dall-e-3"
  private def isGptImageModel: Boolean = config.model.startsWith("gpt-image")

  private def maxPromptLength: Int =
    if (isGptImageModel) 32000
    else if (isDallE2Model) 1000
    else 4000

  private def sizeToApiFormat(size: ImageSize): String =
    size match {
      case ImageSize.Square512 =>
        if (isDallE2Model) "512x512" else "1024x1024"
      case ImageSize.Square1024 =>
        "1024x1024"
      case ImageSize.Landscape768x512 =>
        if (isDallE3Model) "1792x1024"
        else if (isDallE2Model) "512x512"
        else "1536x1024"
      case ImageSize.Portrait512x768 =>
        if (isDallE3Model) "1024x1792"
        else if (isDallE2Model) "512x512"
        else "1024x1536"
      case ImageSize.Landscape1536x1024 =>
        if (isDallE3Model) "1792x1024" else "1536x1024"
      case ImageSize.Portrait1024x1536 =>
        if (isDallE3Model) "1024x1792" else "1024x1536"
      case ImageSize.Auto =>
        "auto"
      case ImageSize.Custom(w, h) =>
        s"${w}x${h}"
    }

  private def makeApiRequest(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, HttpResponse] = {
    val requestBody = Obj(
      "model"  -> Str(config.model),
      "prompt" -> Str(prompt),
      "n"      -> Num(count.toDouble),
      "size"   -> Str(sizeToApiFormat(options.size))
    )

    options.responseFormat.foreach(v => requestBody("response_format") = Str(v))
    options.quality.foreach(v => requestBody("quality") = Str(v))
    options.style.foreach(v => requestBody("style") = Str(v))
    options.background.foreach(v => requestBody("background") = Str(v))
    options.outputFormat.foreach(v => requestBody("output_format") = Str(v))
    options.outputCompression.foreach(v => requestBody("output_compression") = Num(v.toDouble))
    options.user.foreach(v => requestBody("user") = Str(v))

    if (isDallE3Model && !requestBody.obj.contains("quality")) requestBody("quality") = "standard"
    if (isGptImageModel && !requestBody.obj.contains("quality")) requestBody("quality") = "medium"

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
        if (response.statusCode == 200) {
          Right(response)
        } else {
          handleErrorResponse(response)
        }
      }
  }

  private def handleErrorResponse(response: HttpResponse): Either[ImageGenerationError, Nothing] = {
    val errorMessage = Try {
      val json = read(response.body)
      json("error")("message").str
    }.toEither.fold(_ => response.body, identity)

    response.statusCode match {
      case 401  => Left(AuthenticationError("Invalid API key"))
      case 429  => Left(RateLimitError("Rate limit exceeded"))
      case 400  => Left(ValidationError(s"Invalid request: $errorMessage"))
      case code => Left(ServiceError(s"API error: $errorMessage", code))
    }
  }

  private def parseResponse(
    response: HttpResponse,
    prompt: String,
    size: ImageSize,
    fallbackFormat: ImageFormat,
    requestedOutputFormat: Option[String],
    seed: Option[Long]
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    Try {
      val json       = read(response.body)
      val imagesData = json("data").arr

      val images = imagesData.map { imageData =>
        val maybeBase64Data = imageData.obj.get("b64_json").collect { case Str(value) => value }
        val maybeUrl        = imageData.obj.get("url").collect { case Str(value) => value }
        val resolvedFormat = resolveImageFormat(
          maybeBase64Data = maybeBase64Data,
          maybeUrl = maybeUrl,
          requestedOutputFormat = requestedOutputFormat,
          fallbackFormat = fallbackFormat
        )

        GeneratedImage(
          data = maybeBase64Data.getOrElse(""),
          format = resolvedFormat,
          size = size,
          createdAt = Instant.now(),
          prompt = prompt,
          seed = seed,
          filePath = None,
          url = maybeUrl
        )
      }.toSeq

      logger.info(s"Successfully generated ${images.length} image(s)")
      images
    }.toEither.left.map(UnknownError.apply)

  private def warnIfDeprecatedModelConfigured(): Unit =
    if (isDallE2Model || isDallE3Model) {
      logger.warn(OpenAIImageClient.deprecationWarningMessage(config.model))
    }

  private def resolveImageFormat(
    maybeBase64Data: Option[String],
    maybeUrl: Option[String],
    requestedOutputFormat: Option[String],
    fallbackFormat: ImageFormat
  ): ImageFormat = {
    val requested = requestedOutputFormat.flatMap(OpenAIImageClient.outputFormatToImageFormat)
    val fromUrl   = maybeUrl.flatMap(OpenAIImageClient.urlToImageFormat)
    val fromPayload =
      if (maybeBase64Data.isDefined) requested.orElse(Some(fallbackFormat)) else requested.orElse(fromUrl)
    fromPayload.getOrElse(fallbackFormat)
  }
}

object OpenAIImageClient {
  private val DeprecationsUrl       = "https://platform.openai.com/docs/deprecations"
  private val DalleRemovalDate      = "2026-05-12"
  private val MigrationTargetModels = "gpt-image-1, gpt-image-1-mini, gpt-image-1.5"

  def deprecationWarningMessage(model: String): String =
    s"$model is deprecated and scheduled for removal on $DalleRemovalDate. Migrate to $MigrationTargetModels. See $DeprecationsUrl."

  def outputFormatToImageFormat(outputFormat: String): Option[ImageFormat] =
    outputFormat.toLowerCase match {
      case "png"          => Some(ImageFormat.PNG)
      case "jpeg" | "jpg" => Some(ImageFormat.JPEG)
      case "webp"         => Some(ImageFormat.WEBP)
      case _              => None
    }

  def urlToImageFormat(url: String): Option[ImageFormat] = {
    val lower = url.toLowerCase
    if (lower.endsWith(".png")) Some(ImageFormat.PNG)
    else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) Some(ImageFormat.JPEG)
    else if (lower.endsWith(".webp")) Some(ImageFormat.WEBP)
    else None
  }
}
