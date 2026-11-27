package org.llm4s.imagegeneration.provider

import org.llm4s.http.{ HttpResponse, MultipartPart }
import org.llm4s.imagegeneration._
import org.llm4s.llmconnect.config.ProviderConfig
import org.llm4s.media.{ ImageMediaType, MediaType }
import org.slf4j.LoggerFactory
import ujson._

import java.nio.file.Path
import java.time.Instant
import scala.concurrent.{ ExecutionContext, Future, blocking }
import scala.util.Try
import scala.concurrent.duration.*
import org.llm4s.error.LLMError
import org.llm4s.util.Redaction

/**
 * OpenAI Images API client for image generation and editing.
 *
 * Built for the GPT Image models (any id starting `gpt-image`; the default is [[OpenAIConfig.DefaultModel]]), which
 * always return base64 and take `output_format`, `output_compression` and `background` but not `response_format` or
 * `style`. DALL·E ids are still sent as given, for OpenAI-compatible servers that accept them; OpenAI itself shut
 * DALL·E down on 2026-05-12, and the client logs a warning for any model OpenAI has shut down or is shutting down.
 */
class OpenAIImageClient(config: OpenAIConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = LoggerFactory.getLogger(getClass)
  OpenAIImageClient.retirementWarning(config.model).foreach(logger.warn)

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, GeneratedImage] =
    generateImages(prompt, 1, options)
      .flatMap(_.headOption.toRight(ImageValidationError("No images returned from OpenAI image generation endpoint")))

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, Seq[GeneratedImage]] = {
    logger.info(s"Generating $count image(s) with prompt: ${prompt.take(100)}...")

    for {
      _           <- requireApiKey
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
  ): Either[LLMError, Seq[GeneratedImage]] = {
    val validated = for {
      _             <- requireApiKey
      _             <- validatePrompt(prompt)
      _             <- validateCount(options.n)
      openAIOptions <- extractOpenAIEditOptions(options)
      _             <- validateEditResponseFormat(openAIOptions.responseFormat)
      _             <- validateStyle(openAIOptions.style)
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
        MultipartPart.TextField("model", config.model)
      )
      // GPT Image models always return base64 and reject response_format; the legacy models default to a URL.
      if (!isGptImageModel)
        parts += MultipartPart.TextField("response_format", openAIOptions.responseFormat.getOrElse("b64_json"))
      maskPath.foreach(path => parts += MultipartPart.FilePart("mask", path, path.getFileName.toString))
      parts += MultipartPart.TextField("size", sizeToApiFormat(requestedSize))
      openAIOptions.user.foreach(u => parts += MultipartPart.TextField("user", u))
      openAIOptions.quality.foreach(q => parts += MultipartPart.TextField("quality", q))
      openAIOptions.style.foreach(s => parts += MultipartPart.TextField("style", s))

      httpClient
        .postMultipart(
          editUrl,
          headers = Map("Authorization" -> s"Bearer ${config.apiKey.reveal}"),
          data = parts.toSeq,
          timeout = config.timeout
        )
        .toEither
        .left
        .map(e => ImageErrors.fromThrowable(e, "openai-image.request")(ImageUnknownError.apply))
        .flatMap { response =>
          if (response.statusCode == 200) {
            parseResponse(
              response = response,
              prompt = prompt,
              size = requestedSize,
              fallbackFormat = MediaType.Png,
              requestedOutputFormat = None,
              seed = None
            ).flatMap(images =>
              Either
                .cond(
                  images.nonEmpty,
                  images,
                  ImageValidationError("No images returned from OpenAI image edit endpoint")
                )
            )
          } else {
            handleErrorResponse(response)
          }
        }
    }
  }

  private def validateEditResponseFormat(responseFormat: Option[String]): Either[LLMError, Unit] =
    responseFormat match {
      case None                           => Right(())
      case Some("url") if isGptImageModel => Left(gptImageUrlError)
      case Some("b64_json" | "url")       => Right(())
      case Some(other)                    => Left(ImageValidationError(s"Unsupported response format for edit: $other"))
    }

  private def gptImageUrlError: LLMError =
    ImageValidationError(
      s"${config.model} always returns base64 image data: response_format 'url' is only for the retired DALL·E models"
    )

  private def validateStyle(style: Option[String]): Either[LLMError, Unit] =
    if (style.isDefined && isGptImageModel)
      Left(ImageValidationError(s"style is not supported by ${config.model}: describe the style in the prompt"))
    else Right(())

  private def extractOpenAIEditOptions(
    options: ImageEditOptions
  ): Either[LLMError, ProviderImageEditOptions.OpenAI] =
    options.providerOptions match {
      case None                                          => Right(ProviderImageEditOptions.OpenAI())
      case Some(openAI: ProviderImageEditOptions.OpenAI) => Right(openAI)
      case Some(_) =>
        Left(ImageValidationError("Unsupported provider-specific edit options for OpenAI image client"))
    }

  /** A GPT Image model picks the output size itself (`auto`) unless asked; a legacy model keeps the source's size. */
  private def resolveEditOutputSize(
    requestedSize: Option[ImageSize],
    sourceSize: ImageSize
  ): Either[LLMError, ImageSize] =
    Right(requestedSize.getOrElse(if (isGptImageModel) ImageSize.Auto else sourceSize))

  /**
   * The sizes the edits endpoint accepts: for GPT Image models `auto` and the three standard sizes, plus a custom
   * `WIDTHxHEIGHT` (which OpenAI accepts from `gpt-image-2` on and validates itself); for DALL·E 2 the three squares.
   */
  private def validateEditSize(size: ImageSize): Either[LLMError, Unit] = {
    val allowedSizes =
      if (isGptImageModel) Set("auto", "1024x1024", "1536x1024", "1024x1536")
      else Set("256x256", "512x512", "1024x1024")
    val requested = sizeToApiFormat(size)
    val custom = size match {
      case _: ImageSize.Custom => isGptImageModel
      case _                   => false
    }
    Either.cond(
      custom || allowedSizes.contains(requested),
      (),
      ImageValidationError(
        s"Unsupported edit size '$requested' for ${config.model}. Allowed sizes: ${allowedSizes.toSeq.sorted.mkString(", ")}"
      )
    )
  }

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, GeneratedImage]] =
    Future {
      blocking {
        generateImage(prompt, options)
      }
    }.recover { case ex => Left(ImageErrors.fromThrowable(ex, "openai-image.request")(ImageUnknownError.apply)) }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    Future {
      blocking {
        generateImages(prompt, count, options)
      }
    }.recover { case ex => Left(ImageErrors.fromThrowable(ex, "openai-image.request")(ImageUnknownError.apply)) }

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    Future {
      blocking {
        editImage(imagePath, prompt, maskPath, options)
      }
    }.recover { case ex => Left(ImageErrors.fromThrowable(ex, "openai-image.request")(ImageUnknownError.apply)) }

  override def health(): Either[LLMError, ServiceStatus] = {
    val healthUrl = s"${config.baseUrl.stripSuffix("/images/generations").stripSuffix("/v1")}/v1/models"

    requireApiKey
      .flatMap { _ =>
        httpClient
          .get(
            healthUrl,
            headers = Map("Authorization" -> s"Bearer ${config.apiKey.reveal}"),
            timeout = 5.seconds
          )
          .toEither
          .left
          .map(e =>
            ImageErrors.fromThrowable(e, "openai-image.health")(ex =>
              ImageServiceError(s"Health check failed: ${ex.getMessage}", 0)
            )
          )
      }
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

  /** A blank `apiKey` would be sent as `Authorization: Bearer `: refused before any request. */
  private def requireApiKey: Either[LLMError, Unit] =
    ProviderConfig.nonEmpty("OpenAI image", "apiKey", config.apiKey.reveal)

  private def validatePrompt(prompt: String): Either[LLMError, String] =
    if (prompt.trim.isEmpty) {
      Left(ImageValidationError("Prompt cannot be empty"))
    } else if (prompt.length > maxPromptLength) {
      Left(ImageValidationError(s"Prompt cannot exceed $maxPromptLength characters for ${config.model}"))
    } else {
      Right(prompt)
    }

  private def validateCount(count: Int): Either[LLMError, Int] = {
    val maxCount = if (isDallE3Model) 1 else 10
    if (count < 1 || count > maxCount) {
      Left(ImageValidationError(s"Count must be between 1 and $maxCount for ${config.model}"))
    } else {
      Right(count)
    }
  }

  private def validateGenerationOptions(options: ImageGenerationOptions): Either[LLMError, Unit] =
    for {
      _ <- validateResponseFormat(options.responseFormat)
      _ <- validateStyle(options.style)
      _ <- validateOutputFormat(options.outputFormat)
      _ <- validateOutputCompression(options.outputCompression)
      _ <- validateModelOptionCompatibility(options)
    } yield ()

  private def validateResponseFormat(responseFormat: Option[String]): Either[LLMError, Unit] =
    responseFormat match {
      case None                           => Right(())
      case Some("url") if isGptImageModel => Left(gptImageUrlError)
      case Some("b64_json" | "url")       => Right(())
      case Some(unsupported) => Left(ImageValidationError(s"Unsupported response format for generation: $unsupported"))
    }

  private def validateOutputFormat(outputFormat: Option[String]): Either[LLMError, Unit] =
    outputFormat match {
      case None                          => Right(())
      case Some("png" | "jpeg" | "webp") => Right(())
      case Some(other)                   => Left(ImageValidationError(s"Unsupported output format: $other"))
    }

  private def validateOutputCompression(outputCompression: Option[Int]): Either[LLMError, Unit] =
    outputCompression match {
      case None                                      => Right(())
      case Some(level) if level >= 0 && level <= 100 => Right(())
      case Some(level) => Left(ImageValidationError(s"Output compression must be between 0 and 100, got: $level"))
    }

  private def validateModelOptionCompatibility(options: ImageGenerationOptions): Either[LLMError, Unit] =
    if (
      !isGptImageModel && (options.outputFormat.isDefined || options.outputCompression.isDefined || options.background.isDefined)
    ) {
      Left(
        ImageValidationError(
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
  ): Either[LLMError, HttpResponse] = {
    val requestBody = Obj(
      "model"  -> Str(config.model),
      "prompt" -> Str(prompt),
      "n"      -> Num(count.toDouble),
      "size"   -> Str(sizeToApiFormat(options.size))
    )

    // GPT Image models always return base64 and reject response_format
    if (!isGptImageModel) options.responseFormat.foreach(v => requestBody("response_format") = Str(v))
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
          "Authorization" -> s"Bearer ${config.apiKey.reveal}",
          "Content-Type"  -> "application/json"
        ),
        data = requestBody.toString,
        timeout = config.timeout
      )
      .toEither
      .left
      .map(e => ImageErrors.fromThrowable(e, "openai-image.request")(ImageUnknownError.apply))
      .flatMap { response =>
        if (response.statusCode == 200) {
          Right(response)
        } else {
          handleErrorResponse(response)
        }
      }
  }

  // The provider's message, or the whole body when it has none, redacted and capped: either can echo the request's
  // credentials (#1674).
  private def handleErrorResponse(response: HttpResponse): Either[LLMError, Nothing] = {
    val errorMessage = Redaction.safeBody(Try {
      val json = read(response.body)
      json("error")("message").str
    }.toEither.fold(_ => response.body, identity))

    response.statusCode match {
      case 401  => Left(ImageAuthenticationError("Invalid API key"))
      case 429  => Left(ImageRateLimitError("Rate limit exceeded"))
      case 400  => Left(ImageValidationError(s"Invalid request: $errorMessage"))
      case code => Left(ImageServiceError(s"API error: $errorMessage", code))
    }
  }

  private def parseResponse(
    response: HttpResponse,
    prompt: String,
    size: ImageSize,
    fallbackFormat: ImageMediaType,
    requestedOutputFormat: Option[String],
    seed: Option[Long]
  ): Either[LLMError, Seq[GeneratedImage]] =
    Try {
      val json       = read(response.body)
      val imagesData = json("data").arr
      // GPT Image models report the format they wrote at the top level
      val outputFormat = requestedOutputFormat.orElse(json.obj.get("output_format").collect { case Str(v) => v })

      val images = imagesData.map { imageData =>
        val maybeBase64Data = imageData.obj.get("b64_json").collect { case Str(value) => value }
        val maybeUrl        = imageData.obj.get("url").collect { case Str(value) => value }
        val resolvedFormat = resolveImageFormat(
          maybeBase64Data = maybeBase64Data,
          maybeUrl = maybeUrl,
          requestedOutputFormat = outputFormat,
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

      images
    }.toEither.left
      .map(e => ImageErrors.fromThrowable(e, "openai-image.request")(ImageUnknownError.apply))
      .flatMap { images =>
        // An entry with neither `b64_json` nor `url` is no image at all, not an empty one (#3140).
        if (images.exists(i => i.data.isEmpty && i.url.isEmpty))
          Left(ImageValidationError("The response holds an image entry with neither b64_json nor url"))
        else {
          logger.info(s"Successfully generated ${images.length} image(s)")
          Right(images)
        }
      }

  private def resolveImageFormat(
    maybeBase64Data: Option[String],
    maybeUrl: Option[String],
    requestedOutputFormat: Option[String],
    fallbackFormat: ImageMediaType
  ): ImageMediaType = {
    val requested = requestedOutputFormat.flatMap(OpenAIImageClient.outputFormatToImageFormat)
    val fromUrl   = maybeUrl.flatMap(OpenAIImageClient.urlToImageFormat)
    val fromPayload =
      if (maybeBase64Data.isDefined) requested.orElse(Some(fallbackFormat)) else requested.orElse(fromUrl)
    fromPayload.getOrElse(fallbackFormat)
  }
}

object OpenAIImageClient {
  private val DeprecationsUrl = "https://developers.openai.com/api/docs/deprecations"

  /**
   * The image models OpenAI has shut down or announced a shutdown for, with the date and the replacements OpenAI
   * names (its deprecations page, checked 2026-11-24).
   */
  private val Retirements: Map[String, (String, String)] = {
    val gen26 = "gpt-image-2.6-sunburst or gpt-image-2.6-flare"
    Map(
      "dall-e-2"                 -> ("2026-05-12", gen26),
      "dall-e-3"                 -> ("2026-05-12", gen26),
      "gpt-image-1"              -> ("2026-10-23", "gpt-image-2.5-sunburst or gpt-image-2.5-flare"),
      "gpt-image-1-mini"         -> ("2026-12-01", gen26),
      "gpt-image-1.5"            -> ("2026-12-01", gen26),
      "gpt-image-1.5-2025-12-16" -> ("2026-12-01", gen26),
      "chatgpt-image-latest"     -> ("2026-12-01", gen26),
      "gpt-image-2"              -> ("2027-02-10", gen26),
      "gpt-image-2-2026-04-21"   -> ("2027-02-10", gen26)
    )
  }

  /** The warning logged when the client is built for a model OpenAI has shut down or will shut down, if it is one. */
  def retirementWarning(model: String): Option[String] =
    Retirements.get(model).map { case (date, replacements) =>
      s"OpenAI shuts down $model on $date, after which api.openai.com refuses it. Use $replacements. " +
        s"See $DeprecationsUrl."
    }

  /**
   * The image formats the OpenAI images API can return. The vocabulary in `llm4s-media` knows
   * about more of them than this provider will ever produce, so both lookups below are
   * filtered through this rather than reporting a format the API cannot have sent.
   */
  private val supportedFormats: Set[ImageMediaType] = Set(MediaType.Png, MediaType.Jpeg, MediaType.WebP)

  def outputFormatToImageFormat(outputFormat: String): Option[ImageMediaType] =
    MediaType.imageFromExtension(outputFormat).filter(supportedFormats.contains)

  def urlToImageFormat(url: String): Option[ImageMediaType] =
    MediaType.imageFromPath(url).filter(supportedFormats.contains)
}
