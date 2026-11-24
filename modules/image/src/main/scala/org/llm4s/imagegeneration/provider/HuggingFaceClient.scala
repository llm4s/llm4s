package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._

import java.time.Instant
import java.nio.file.Path
import java.util.Base64
import scala.util.Try
import scala.concurrent.duration.*
import scala.concurrent.{ Future, ExecutionContext, blocking }
import org.llm4s.error.LLMError
import org.llm4s.util.Redaction

/**
 * Hugging Face Inference Providers client for image generation, through Hugging Face's own `hf-inference` provider.
 *
 * It posts the documented text-to-image request (`inputs` plus `parameters`) to
 * `<baseUrl>/models/<model>` on the Inference Providers router and reads the image back as raw bytes
 * ([[https://huggingface.co/docs/inference-providers/tasks/text-to-image text-to-image task]]). The legacy
 * `api-inference.huggingface.co` host is not used. The model must be one `hf-inference` serves for text-to-image;
 * models served only by third-party providers (fal, Replicate, ...) need those providers' own request formats.
 *
 * @param config Configuration containing API key, model and base URL
 *
 * @example
 * {{{
 * val config = HuggingFaceConfig(
 *   apiKey = Secret("your-hf-token"),
 *   model = "stabilityai/stable-diffusion-3-medium-diffusers"
 * )
 * val client = new HuggingFaceClient(config, HttpClient())
 *
 * client.generateImage("a beautiful sunset over mountains") match {
 *   case Right(image) => println(s"Generated image: $${image.size}")
 *   case Left(error) => println(s"Error: $${error.message}")
 * }
 * }}}
 */
class HuggingFaceClient(config: HuggingFaceConfig, httpClient: HttpClient) extends ImageGenerationClient {

  private val logger = org.slf4j.LoggerFactory.getLogger(getClass)

  /**
   * Generate a single image from a text prompt using HuggingFace Inference API.
   *
   * @param prompt The text description of the image to generate
   * @param options Optional generation parameters like size, guidance scale, etc.
   * @return Either an error or the generated image
   */
  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  /**
   * Validates the provided prompt to ensure it is not empty or blank.
   *
   * @param prompt The input string representing the prompt to validate.
   * @return Either an `ImageGenerationError` if the validation fails, or the original valid prompt as a `String`.
   */
  def validatePrompt(prompt: String): Either[LLMError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ImageValidationError("Prompt cannot be empty"))

  /**
   * Validates the provided count to ensure it is within the allowable range
   * for image generation (1 to 4 inclusive, for HuggingFace).
   *
   * @param count The number of images requested for generation. Must be between 1 and 4.
   * @return Either an `ImageGenerationError` if the count is out of range,
   *         or the valid count as an `Int` if the validation succeeds.
   */
  def validateCount(count: Int): Either[LLMError, Int] =
    Either.cond(count > 0 && count <= 4, count, ImageValidationError("Count must be between 1 and 4 for HuggingFace"))

  /**
   * Base64-encodes raw image bytes received directly from the HTTP layer.
   *
   * Takes the exact wire bytes so no charset round-trip occurs. The previous
   * approach (`response.body.getBytes(ISO_8859_1)`) was lossy: `BodyHandlers.ofString`
   * decoded bytes as UTF-8, replacing invalid sequences with U+FFFD, which
   * ISO_8859_1 then mapped to `?` (0x3F), corrupting binary image data.
   *
   * @param imageBytes Raw bytes from the HTTP response body.
   * @return Either an `ImageGenerationError` in case of a failure or
   *         the resulting Base64-encoded string on success.
   */
  def convertToBase64(imageBytes: Array[Byte]): Either[LLMError, String] = Try {
    Base64.getEncoder.encodeToString(imageBytes)
  }.toEither.left.map(exception =>
    ImageErrors.fromThrowable(exception, "huggingface-image.request")(ex => ImageServiceError(ex.getMessage, 500))
  )

  /**
   * Generates multiple images based on the given text prompt using predefined options and base64-encoded image data.
   *
   * This method creates a sequence of `GeneratedImage` objects by iteratively adding offsets to the provided seed
   * (if present in the options). It handles errors by returning an `ImageGenerationError` wrapped in an `Either`.
   *
   * @param prompt     The text description of the images to be generated.
   * @param count      The number of images to generate. Ensures the specified number of iterations in the generation process.
   * @param options    Optional parameters for image generation, including size, format, seed, guidance scale, etc.
   * @param base64Data The base64-encoded image data that will be used to populate each generated image.
   * @return Either an `ImageGenerationError` in case of a generation issue, or an `IndexedSeq` of `GeneratedImage` objects
   *         containing all successfully created images.
   */
  def generateAllImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions(),
    base64Data: String
  ): Either[LLMError, IndexedSeq[GeneratedImage]] = Try {
    logger.debug("Generating {} image(s) with HuggingFace: '{}'", count, prompt)

    val images = (1 to count).map { i =>
      GeneratedImage(
        data = base64Data,
        format = options.format,
        size = options.size,
        prompt = prompt,
        seed = options.seed.map(_ + i),
        createdAt = Instant.now()
      )
    }
    (1 to count).foreach(i => logger.debug("Generated image: {}", i))
    images
  }.toEither.left.map(exception =>
    ImageErrors.fromThrowable(exception, "huggingface-image.request")(ex => ImageServiceError(ex.getMessage, 500))
  )

  /**
   * Generate multiple images from a text prompt using HuggingFace Inference API.
   *
   * Note: HuggingFace Inference API typically returns one image per request, so multiple
   * images are generated by making the same request multiple times with different seeds.
   *
   * @param prompt The text description of the images to generate
   * @param count Number of images to generate (1-4)
   * @param options Optional generation parameters like size, guidance scale, etc.
   * @return Either an error or a sequence of generated images
   */
  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, Seq[GeneratedImage]] = {

    val result: Either[LLMError, IndexedSeq[GeneratedImage]] = for {
      prompt     <- validatePrompt(prompt)
      count      <- validateCount(count)
      payload    <- buildPayload(prompt, options)
      rawBytes   <- makeHttpRequest(payload)
      base64Data <- convertToBase64(rawBytes)
      images     <- generateAllImages(prompt, count, options, base64Data)
    } yield images

    result.left.foreach(error => logger.error("Error generating images: {}", error.message))

    result
  }

  /**
   * Edit an existing image based on a prompt and optional mask.
   *
   * Not currently supported for HuggingFace provider.
   */
  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[LLMError, Seq[GeneratedImage]] =
    Left(UnsupportedOperation("Image editing is not yet supported for HuggingFace provider"))

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, GeneratedImage]] =
    Future {
      blocking {
        generateImage(prompt, options)
      }
    }.recover { case ex => Left(ImageErrors.fromThrowable(ex, "huggingface-image.request")(ImageUnknownError.apply)) }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    Future {
      blocking {
        generateImages(prompt, count, options)
      }
    }.recover { case ex => Left(ImageErrors.fromThrowable(ex, "huggingface-image.request")(ImageUnknownError.apply)) }

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
    }.recover { case ex => Left(ImageErrors.fromThrowable(ex, "huggingface-image.request")(ImageUnknownError.apply)) }

  /**
   * Check whether Hugging Face's `hf-inference` provider serves the configured model.
   *
   * Reads the model's provider mapping from the Hub API
   * (`GET https://huggingface.co/api/models/<model>?expand[]=inferenceProviderMapping`,
   * [[https://huggingface.co/docs/inference-providers/hub-api#get-model-providers Hub API]]): healthy when
   * `hf-inference` is listed with status `live`, degraded when it is missing, staging or the Hub answers with an error.
   *
   * @return Either an error or the current service status
   */
  override def health(): Either[LLMError, ServiceStatus] = {
    val headers = Map("Authorization" -> s"Bearer ${config.apiKey.reveal}")

    httpClient
      .get(HuggingFaceClient.providerMappingUrl(config.model), headers, 10.seconds)
      .toEither
      .left
      .map(e =>
        ImageErrors.fromThrowable(e, "huggingface-image.health")(ex =>
          ImageServiceError(s"Health check failed: ${ex.getMessage}", 0)
        )
      )
      .map { response =>
        if (response.statusCode != 200)
          ServiceStatus(HealthStatus.Degraded, s"Service returned status code: ${response.statusCode}")
        else
          HuggingFaceClient.hfInferenceStatus(response.body) match {
            case Some("live") =>
              ServiceStatus(HealthStatus.Healthy, s"hf-inference serves ${config.model}")
            case Some(other) =>
              ServiceStatus(HealthStatus.Degraded, s"hf-inference lists ${config.model} as $other")
            case None =>
              ServiceStatus(HealthStatus.Degraded, s"hf-inference does not serve ${config.model}")
          }
      }
  }

  /**
   * Serializes a `HuggingClientPayload` object into a JSON string.
   *
   * @param huggingClientPayload The payload object containing input text and generation parameters
   *                             to be serialized into JSON format.
   * @return The JSON string representation of the provided `HuggingClientPayload` object.
   */
  def createJsonPayload(huggingClientPayload: HuggingClientPayload): String = {
    val json = upickle.default.writeJs(huggingClientPayload)
    // Leave an absent optional parameter out rather than sending it as null.
    json("parameters").obj.filterInPlace { case (_, value) => value != ujson.Null }
    ujson.write(json)
  }

  /**
   * Builds the JSON payload required for the HuggingFace Inference API
   * based on the provided prompt and image generation options.
   *
   * @param prompt  The text description for the image generation.
   * @param options The image generation options such as size, seed, guidance scale, etc.
   * @return Either an `ImageGenerationError` if payload creation fails,
   *         or the JSON string representing the payload.
   */
  def buildPayload(prompt: String, options: ImageGenerationOptions): Either[LLMError, String] = Try {
    val payload = HuggingClientPayload(prompt, options)
    val jsonStr = createJsonPayload(payload)
    logger.debug("Payload: {} - Json: {}", payload, jsonStr)
    jsonStr
  }.toEither.left.map(exception =>
    ImageErrors.fromThrowable(exception, "huggingface-image.request")(ex => ImageServiceError(ex.getMessage, 500))
  )

  /**
   * Makes an HTTP POST request to `<baseUrl>/models/<model>` and returns the raw response bytes.
   *
   * Uses `postRaw` so the binary image payload is never decoded through a charset,
   * preventing the corruption that occurred when `BodyHandlers.ofString` decoded bytes
   * as UTF-8 and they were then re-encoded with ISO_8859_1.
   *
   * @param payload The JSON payload to send with the HTTP request.
   * @return Either an ImageGenerationError if the request fails, or the raw image bytes on success.
   */
  def makeHttpRequest(payload: String): Either[LLMError, Array[Byte]] = {
    val url = s"${config.baseUrl.stripSuffix("/")}/models/${config.model}"
    val headers = Map(
      "Authorization" -> s"Bearer ${config.apiKey.reveal}",
      "Content-Type"  -> "application/json"
    )

    httpClient
      .postRaw(url, headers, payload, config.timeout)
      .toEither
      .left
      .map(exception =>
        ImageErrors.fromThrowable(exception, "huggingface-image.request")(ex => ImageServiceError(ex.getMessage, 500))
      )
      .flatMap { response =>
        response.statusCode match {
          case 200 => Right(response.body)
          case 401 => Left(ImageAuthenticationError("Unauthorized"))
          case 429 => Left(ImageRateLimitError("Rate limit"))
          case _ =>
            Left(
              ImageServiceError(
                Redaction.safeBody(new String(response.body, java.nio.charset.StandardCharsets.UTF_8)),
                500
              )
            )
        }
      }
  }
}

private[provider] object HuggingFaceClient {

  /** The Hub API URL that lists the providers serving `model`. */
  def providerMappingUrl(model: String): String =
    s"https://huggingface.co/api/models/$model?expand[]=inferenceProviderMapping"

  /** The `hf-inference` entry's status in a Hub API model-info body, if it lists one. */
  def hfInferenceStatus(body: String): Option[String] =
    scala.util
      .Try(ujson.read(body))
      .toOption
      .flatMap(_.objOpt)
      .flatMap(_.get("inferenceProviderMapping"))
      .flatMap(mapping => providerStatus(mapping, "hf-inference"))

  // The Hub has answered with an object keyed by provider and with an array of entries naming the provider.
  private def providerStatus(mapping: ujson.Value, provider: String): Option[String] =
    mapping match {
      case obj: ujson.Obj =>
        obj.value
          .get(provider)
          .map(entry => entry.objOpt.flatMap(_.get("status")).flatMap(_.strOpt).getOrElse("unknown"))
      case arr: ujson.Arr =>
        arr.value
          .flatMap(_.objOpt)
          .find(_.get("provider").flatMap(_.strOpt).contains(provider))
          .map(_.get("status").flatMap(_.strOpt).getOrElse("unknown"))
      case _ => None
    }
}
