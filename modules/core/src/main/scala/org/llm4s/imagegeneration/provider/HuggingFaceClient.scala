package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._

import java.time.Instant
import java.util.Base64
import scala.util.Try

/**
 * HuggingFace Inference API client for image generation.
 *
 * This client provides access to HuggingFace's hosted diffusion models through their
 * Inference API. It supports popular models like Stable Diffusion and other text-to-image
 * models available on the HuggingFace Hub.
 *
 * @param config Configuration containing API key and model settings
 *
 * @example
 * {{{
 * val config = HuggingFaceConfig(
 *   apiKey = "your-hf-token",
 *   model = "stabilityai/stable-diffusion-2-1"
 * )
 * val client = new HuggingFaceClient(config)
 *
 * client.generateImage("a beautiful sunset over mountains") match {
 *   case Right(image) => println(s"Generated image: $${image.size}")
 *   case Left(error) => println(s"Error: $${error.message}")
 * }
 * }}}
 */
class HuggingFaceClient(config: HuggingFaceConfig, httpClient: BaseHttpClient) extends ImageGenerationClient {

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
  ): Either[ImageGenerationError, GeneratedImage] =
    generateImages(prompt, 1, options).map(_.head)

  /**
   * Validates the provided prompt to ensure it is not empty or blank.
   *
   * @param prompt The input string representing the prompt to validate.
   * @return Either an `ImageGenerationError` if the validation fails, or the original valid prompt as a `String`.
   */
  def validatePrompt(prompt: String): Either[ImageGenerationError, String] =
    Either.cond(prompt.trim.nonEmpty, prompt, ValidationError("Prompt cannot be empty"))

  /**
   * Validates the provided count to ensure it is within the allowable range
   * for image generation (1 to 4 inclusive, for HuggingFace).
   *
   * @param count The number of images requested for generation. Must be between 1 and 4.
   * @return Either an `ImageGenerationError` if the count is out of range,
   *         or the valid count as an `Int` if the validation succeeds.
   */
  def validateCount(count: Int): Either[ImageGenerationError, Int] =
    Either.cond(count > 0 && count <= 4, count, ValidationError("Count must be between 1 and 4 for HuggingFace"))

  /**
   * Converts the byte content of an HTTP response into a Base64-encoded string.
   * If an error occurs during the conversion process, it wraps the exception
   * into an `ImageGenerationError`.
   *
   * @param response The HTTP response containing the byte data to convert.
   * @return Either an `ImageGenerationError` in case of a failure or
   *         the resulting Base64-encoded string on success.
   */
  def convertToBase64(response: requests.Response): Either[ImageGenerationError, String] = Try {
    val imageData = response.bytes
    Base64.getEncoder.encodeToString(imageData)
  }.toEither.left.map(exception => ServiceError(exception.getMessage, 500))

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
  ): Either[ImageGenerationError, IndexedSeq[GeneratedImage]] = Try {
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
  }.toEither.left.map(exception => ServiceError(exception.getMessage, 500))

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
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

    val result: Either[ImageGenerationError, IndexedSeq[GeneratedImage]] = for {
      prompt     <- validatePrompt(prompt)
      count      <- validateCount(count)
      payload    <- buildPayload(prompt, options)
      response   <- makeHttpRequest(payload)
      base64Data <- convertToBase64(response)
      images     <- generateAllImages(prompt, count, options, base64Data)
    } yield images

    result.left.foreach(error => logger.error("Error generating images: {}", error.message))

    result
  }

  /**
   * Check the health status of the HuggingFace Inference API.
   *
   * @return Either an error or the current service status
   */
  override def health(): Either[ImageGenerationError, ServiceStatus] =
    scala.util
      .Try {
        val testUrl = s"https://api-inference.huggingface.co/models/${config.model}"
        val headers = Map(
          "Authorization" -> s"Bearer ${config.apiKey}",
          "Content-Type"  -> "application/json"
        )
        requests.get(testUrl, headers = headers, readTimeout = 10000)
      }
      .toEither
      .left
      .map(e => ServiceError(s"Health check failed: ${e.getMessage}", 0))
      .map { response =>
        if (response.statusCode == 200)
          ServiceStatus(HealthStatus.Healthy, "HuggingFace Inference API is responding")
        else ServiceStatus(HealthStatus.Degraded, s"Service returned status code: ${response.statusCode}")
      }

  /**
   * Serializes a `HuggingClientPayload` object into a JSON string.
   *
   * @param huggingClientPayload The payload object containing input text and generation parameters
   *                             to be serialized into JSON format.
   * @return The JSON string representation of the provided `HuggingClientPayload` object.
   */
  def createJsonPayload(huggingClientPayload: HuggingClientPayload): String =
    upickle.default.write(huggingClientPayload)

  /**
   * Builds the JSON payload required for the HuggingFace Inference API
   * based on the provided prompt and image generation options.
   *
   * @param prompt  The text description for the image generation.
   * @param options The image generation options such as size, seed, guidance scale, etc.
   * @return Either an `ImageGenerationError` if payload creation fails,
   *         or the JSON string representing the payload.
   */
  def buildPayload(prompt: String, options: ImageGenerationOptions): Either[ImageGenerationError, String] = Try {
    val payload = HuggingClientPayload(prompt, options)
    val jsonStr = createJsonPayload(payload)
    logger.debug("Payload: {} - Json: {}", payload, jsonStr)
    jsonStr
  }.toEither.left.map(exception => ServiceError(exception.getMessage, 500))

  /**
   * Makes an HTTP POST request to the HuggingFace Inference API to send a payload and retrieve a response.
   *
   * @param payload The JSON payload to send with the HTTP request.
   * @return Either an ImageGenerationError if the request fails or a successful `requests.Response` object.
   */
  def makeHttpRequest(payload: String): Either[ImageGenerationError, requests.Response] = {
    val result = Try(httpClient.post(payload)).toEither.left.map(exception => ServiceError(exception.getMessage, 500))
    result.flatMap { response =>
      if (response.statusCode == 200) {
        Right(response)
      } else {
        Left(ServiceError(response.text(), 500))
      }
    }
  }
}
