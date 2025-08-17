package org.llm4s.imagegeneration

import java.time.Instant
import java.nio.file.Path
import org.llm4s.imagegeneration.provider.{ HttpClient, HuggingFaceClient, StableDiffusionClient, OpenAIImageClient }

// ===== ERROR HANDLING =====

sealed trait ImageGenerationError {
  def message: String
}

case class AuthenticationError(message: String)        extends ImageGenerationError
case class RateLimitError(message: String)             extends ImageGenerationError
case class ServiceError(message: String, code: Int)    extends ImageGenerationError
case class ValidationError(message: String)            extends ImageGenerationError
case class InvalidPromptError(message: String)         extends ImageGenerationError
case class InsufficientResourcesError(message: String) extends ImageGenerationError
case class UnknownError(throwable: Throwable) extends ImageGenerationError {
  def message: String = throwable.getMessage
}

// ===== MODELS =====

/** Image size enumeration */
sealed trait ImageSize {
  def width: Int
  def height: Int
  def description: String = s"${width}x${height}"
}

object ImageSize {
  case object Square512 extends ImageSize {
    val width  = 512
    val height = 512
  }
  case object Square1024 extends ImageSize {
    val width  = 1024
    val height = 1024
  }
  case object Landscape768x512 extends ImageSize {
    val width  = 768
    val height = 512
  }
  case object Portrait512x768 extends ImageSize {
    val width  = 512
    val height = 768
  }
}

/** Image format enumeration */
sealed trait ImageFormat {
  def extension: String
  def mimeType: String
}

object ImageFormat {
  case object PNG extends ImageFormat {
    val extension = "png"
    val mimeType  = "image/png"
  }
  case object JPEG extends ImageFormat {
    val extension = "jpg"
    val mimeType  = "image/jpeg"
  }
}

/** Options for image generation */
case class ImageGenerationOptions(
  size: ImageSize = ImageSize.Square512,
  format: ImageFormat = ImageFormat.PNG,
  seed: Option[Long] = None,
  guidanceScale: Double = 7.5,
  inferenceSteps: Int = 20,
  negativePrompt: Option[String] = None,
  samplerName: Option[String] = None // Optional sampler name
)

/** Service health status */
sealed trait HealthStatus
object HealthStatus {
  case object Healthy   extends HealthStatus
  case object Degraded  extends HealthStatus
  case object Unhealthy extends HealthStatus
}

/** Represents the status of the image generation service */
case class ServiceStatus(
  status: HealthStatus,
  message: String,
  lastChecked: Instant = Instant.now(),
  queueLength: Option[Int] = None,
  averageGenerationTime: Option[Long] = None // in milliseconds
)

/** Represents a generated image */
case class GeneratedImage(
  /** Base64 encoded image data */
  data: String,
  /** Image format */
  format: ImageFormat,
  /** Image dimensions */
  size: ImageSize,
  /** Generation timestamp */
  createdAt: Instant = Instant.now(),
  /** Original prompt used */
  prompt: String,
  /** Seed used for generation (if available) */
  seed: Option[Long] = None,
  /** Optional file path if saved to disk */
  filePath: Option[Path] = None
) {

  /** Get the image data as bytes */
  def asBytes: Array[Byte] = {
    import java.util.Base64
    Base64.getDecoder.decode(data)
  }

  /** Save image to file and return updated GeneratedImage with file path */
  def saveToFile(path: Path): Either[ImageGenerationError, GeneratedImage] =
    try {
      import java.nio.file.Files
      Files.write(path, asBytes)
      Right(copy(filePath = Some(path)))
    } catch {
      case e: Exception =>
        Left(UnknownError(e))
    }
}

// ===== CONFIGURATION =====

/** Providers for image generation */
sealed trait ImageGenerationProvider

object ImageGenerationProvider {
  case object StableDiffusion extends ImageGenerationProvider
  case object DALLE           extends ImageGenerationProvider
  case object Midjourney      extends ImageGenerationProvider
  case object HuggingFace     extends ImageGenerationProvider
}

sealed trait ImageGenerationConfig {
  def provider: ImageGenerationProvider
  def timeout: Int = 30000 // 30 seconds default
}

/** Configuration for Stable Diffusion */
case class StableDiffusionConfig(
  /** Base URL of the Stable Diffusion server (e.g., http://localhost:7860) */
  baseUrl: String = "http://localhost:7860",
  /** API key if required */
  apiKey: Option[String] = None,
  /** Request timeout in milliseconds */
  override val timeout: Int = 60000 // 60 seconds for image generation
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.StableDiffusion
}

/**
 * Configuration for the HuggingFace Inference API.
 *
 * @param apiKey Your HuggingFace API token. This is required for authentication.
 * @param model The identifier of the model to use on the HuggingFace Hub, e.g., "runwayml/stable-diffusion-v1-5".
 * @param timeout Request timeout in milliseconds. Defaults to a higher value suitable for cloud APIs.
 */
case class HuggingFaceConfig(
  /** HuggingFace API token */
  apiKey: String,
  /** Model to use (default: stable-diffusion-xl-base-1.0) */
  model: String = "stabilityai/stable-diffusion-xl-base-1.0",
  /** Request timeout in milliseconds */
  override val timeout: Int = 120000 // 2 minutes for cloud generation
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.HuggingFace
}

/**
 * Configuration for OpenAI DALL-E API.
 *
 * @param apiKey Your OpenAI API key. This is required for authentication.
 * @param model The DALL-E model version to use (dall-e-2 or dall-e-3).
 * @param timeout Request timeout in milliseconds.
 */
case class OpenAIConfig(
  /** OpenAI API key */
  apiKey: String,
  /** Model to use (dall-e-2 or dall-e-3) */
  model: String = "dall-e-2",
  /** Request timeout in milliseconds */
  override val timeout: Int = 30000 // 30 seconds for image generation
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.DALLE
}

// ===== CLIENT INTERFACE =====

trait ImageGenerationClient {

  /** Generate an image from a text prompt */
  def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage]

  /** Generate multiple images from a text prompt */
  def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]]

  /** Check the health/status of the image generation service */
  def health(): Either[ImageGenerationError, ServiceStatus]
}

// ===== FACTORY OBJECT =====

object ImageGeneration {

  /** Factory method for getting a client with the right configuration */
  def client(config: ImageGenerationConfig): ImageGenerationClient =
    config match {
      case sdConfig: StableDiffusionConfig =>
        new StableDiffusionClient(sdConfig)
      case hfConfig: HuggingFaceConfig =>
        val httpClient = HttpClient.createHttpClient(hfConfig)
        new HuggingFaceClient(hfConfig, httpClient)
      case openAIConfig: OpenAIConfig =>
        new OpenAIImageClient(openAIConfig)
    }

  /** Convenience method for quick image generation */
  def generateImage(
    prompt: String,
    config: ImageGenerationConfig,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] =
    client(config).generateImage(prompt, options)

  /** Convenience method for generating multiple images */
  def generateImages(
    prompt: String,
    count: Int,
    config: ImageGenerationConfig,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] =
    client(config).generateImages(prompt, count, options)

  /** Get a Stable Diffusion client with default local configuration */
  def stableDiffusionClient(
    baseUrl: String = "http://localhost:7860",
    apiKey: Option[String] = None
  ): ImageGenerationClient = {
    val config = StableDiffusionConfig(baseUrl = baseUrl, apiKey = apiKey)
    client(config)
  }

  /**
   * Get a HuggingFace client with the required API key.
   *
   * This is a convenience method for creating a client that connects to the
   * HuggingFace Inference API for image generation.
   *
   * @param apiKey Your HuggingFace API token (required).
   * @param model The specific model to use for generation. Defaults to a standard Stable Diffusion model.
   * @return An `ImageGenerationClient` instance configured for HuggingFace.
   */
  def huggingFaceClient(
    apiKey: String,
    model: String = "stabilityai/stable-diffusion-xl-base-1.0"
  ): ImageGenerationClient = {
    val config = HuggingFaceConfig(apiKey = apiKey, model = model)
    client(config)
  }

  /**
   * Get an OpenAI DALL-E client with the required API key.
   *
   * This is a convenience method for creating a client that connects to the
   * OpenAI API for DALL-E image generation.
   *
   * @param apiKey Your OpenAI API key (required).
   * @param model The DALL-E model version to use. Defaults to dall-e-2.
   * @return An `ImageGenerationClient` instance configured for OpenAI DALL-E.
   */
  def openAIClient(
    apiKey: String,
    model: String = "dall-e-2"
  ): ImageGenerationClient = {
    val config = OpenAIConfig(apiKey = apiKey, model = model)
    client(config)
  }

  /** Convenience method for quick Stable Diffusion image generation */
  def generateWithStableDiffusion(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions(),
    baseUrl: String = "http://localhost:7860"
  ): Either[ImageGenerationError, GeneratedImage] = {
    val config = StableDiffusionConfig(baseUrl = baseUrl)
    generateImage(prompt, config, options)
  }

  /** Convenience method for quick OpenAI DALL-E image generation */
  def generateWithOpenAI(
    prompt: String,
    apiKey: String,
    options: ImageGenerationOptions = ImageGenerationOptions(),
    model: String = "dall-e-2"
  ): Either[ImageGenerationError, GeneratedImage] = {
    val config = OpenAIConfig(apiKey = apiKey, model = model)
    generateImage(prompt, config, options)
  }

  /** Check service health */
  def healthCheck(config: ImageGenerationConfig): Either[ImageGenerationError, ServiceStatus] =
    client(config).health()
}
