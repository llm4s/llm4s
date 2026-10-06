package org.llm4s.imagegeneration

import java.time.Instant
import scala.concurrent.duration.*
import org.llm4s.media.{ ImageMediaType, MediaType }
import java.nio.file.Path
import org.llm4s.imagegeneration.provider.{
  HttpClient,
  HuggingFaceClient,
  OpenAIImageClient,
  StableDiffusionClient,
  StabilityAIClient
}

import scala.annotation.unused
import scala.util.Try
import scala.concurrent.{ Future, ExecutionContext }
import org.llm4s.metrics.MetricsCollector
import org.llm4s.trace.Tracing
import org.llm4s.error.{ CancelledError, LLMError, NonRecoverableError, RecoverableError }

// ===== ERROR HANDLING =====

/**
 * Why an image generation call failed.
 *
 * An [[org.llm4s.error.LLMError]], so the client methods can return
 * [[org.llm4s.error.CancelledError]] when their thread is interrupted - the contract of every llm4s
 * client - next to these cases: they return `Either[LLMError, _]`.
 *
 * Every case says whether trying again can help, as an `LLMError` must: `LLMError.isRecoverable` (and the
 * retry policies built on it) matches only [[org.llm4s.error.RecoverableError]] and
 * [[org.llm4s.error.NonRecoverableError]]. Recoverable: [[RateLimitError]], and a [[ServiceError]] whose status
 * is transient (see [[ServiceError.isTransientStatus]]). Everything else is not: a rejected credential, request
 * or prompt gets the same answer on the next call, and an [[UnknownError]] is not retried blindly.
 */
sealed trait ImageGenerationError extends LLMError {
  def message: String
}

case class AuthenticationError(message: String) extends ImageGenerationError with NonRecoverableError
case class RateLimitError(message: String)      extends ImageGenerationError with RecoverableError

/**
 * The provider's service failed or refused the call, with the HTTP status it answered (`0` when it did not
 * answer at all, as in a failed health check).
 *
 * Whether trying again can help follows from the status, which a class cannot express by itself, so there
 * are two cases behind this type and [[ServiceError.apply]] picks one: a transient status gives a
 * [[org.llm4s.error.RecoverableError]], any other a [[org.llm4s.error.NonRecoverableError]]. Build and match
 * it as before, `ServiceError(message, status)` and `case ServiceError(message, status)`.
 */
sealed trait ServiceError extends ImageGenerationError {
  def statusCode: Int
  override def code: Option[String] = Some(statusCode.toString)
}

object ServiceError {

  /** Statuses worth retrying: no answer (0), request timeout (408), rate limited (429) and every 5xx. */
  def isTransientStatus(statusCode: Int): Boolean =
    statusCode == 0 || statusCode == 408 || statusCode == 429 || statusCode >= 500

  def apply(message: String, statusCode: Int): ServiceError =
    if (isTransientStatus(statusCode)) TransientServiceError(message, statusCode)
    else RejectedServiceError(message, statusCode)

  def unapply(error: ServiceError): Some[(String, Int)] = Some((error.message, error.statusCode))
}

/** A [[ServiceError]] with a transient status: trying again can help. */
final case class TransientServiceError(message: String, statusCode: Int) extends ServiceError with RecoverableError

/** A [[ServiceError]] the provider refused for good: the same call gets the same answer. */
final case class RejectedServiceError(message: String, statusCode: Int) extends ServiceError with NonRecoverableError

case class ValidationError(message: String)            extends ImageGenerationError with NonRecoverableError
case class InvalidPromptError(message: String)         extends ImageGenerationError with NonRecoverableError
case class InsufficientResourcesError(message: String) extends ImageGenerationError with NonRecoverableError
case class UnsupportedOperation(message: String)       extends ImageGenerationError with NonRecoverableError
case class UnknownError(throwable: Throwable) extends ImageGenerationError with NonRecoverableError {
  def message: String = throwable.getMessage
}

private[imagegeneration] object ImageErrors {

  /**
   * What a failed call is: a [[org.llm4s.error.CancelledError]] if `t` is a cancellation - the thread is
   * interrupted, or `t` has an interruption among its causes (design section 4.4) - otherwise
   * `otherwise(t)`.
   */
  def fromThrowable(t: Throwable, operation: String)(otherwise: Throwable => LLMError): LLMError =
    CancelledError.fromThrowable(t, operation).getOrElse(otherwise(t))
}

// ===== MODELS =====

/** Image size enumeration */
sealed trait ImageSize {
  def width: Int
  def height: Int
  def description: String = s"${width}x$height"
}

object ImageSize {
  case object Auto extends ImageSize {
    val width                        = 0
    val height                       = 0
    override def description: String = "auto"
  }
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
  // New sizes for GPT Image models
  case object Landscape1536x1024 extends ImageSize {
    val width  = 1536
    val height = 1024
  }
  case object Portrait1024x1536 extends ImageSize {
    val width  = 1024
    val height = 1536
  }
  final case class Custom(width: Int, height: Int) extends ImageSize
}

/** Options for image generation */
case class ImageGenerationOptions(
  size: ImageSize = ImageSize.Square512,
  format: ImageMediaType = MediaType.Png,
  seed: Option[Long] = None,
  guidanceScale: Double = 7.5,
  inferenceSteps: Int = 20,
  negativePrompt: Option[String] = None,
  samplerName: Option[String] = None, // Optional sampler name
  // New options for GPT Image models
  quality: Option[String] = None,        // "standard" or "hd"
  style: Option[String] = None,          // "vivid" or "natural"
  responseFormat: Option[String] = None, // "url" or "b64_json"
  outputFormat: Option[String] = None,   // "png", "jpeg", "webp"
  background: Option[String] = None,     // "transparent", "opaque", "auto"
  outputCompression: Option[Int] = None, // 0-100
  user: Option[String] = None            // End-user identifier for abuse monitoring
)

/** Provider-specific edit options; keeps shared API provider-agnostic. */
sealed trait ProviderImageEditOptions
object ProviderImageEditOptions {
  case class OpenAI(
    responseFormat: Option[String] = None,
    quality: Option[String] = None,
    style: Option[String] = None,
    user: Option[String] = None
  ) extends ProviderImageEditOptions
  case class StableDiffusion(
    denoisingStrength: Option[Double] = None
  ) extends ProviderImageEditOptions
}

/** Options for image editing */
case class ImageEditOptions(
  size: Option[ImageSize] = None,
  n: Int = 1,
  providerOptions: Option[ProviderImageEditOptions] = None
)

/** Service health status */
sealed trait HealthStatus
object HealthStatus {
  case object Healthy   extends HealthStatus
  case object Degraded  extends HealthStatus
  case object Unhealthy extends HealthStatus
  case object Unknown   extends HealthStatus
}

/** Represents the status of the image generation service */
case class ServiceStatus(
  status: HealthStatus,
  message: String,
  lastChecked: Instant = Instant.now(),
  queueLength: Option[Int] = None,
  averageGenerationTime: Option[FiniteDuration] = None
)

/** Represents a generated image */
case class GeneratedImage(
  /** Base64 encoded image data */
  data: String,
  /** Image format */
  format: ImageMediaType,
  /** Image dimensions */
  size: ImageSize,
  /** Generation timestamp */
  createdAt: Instant = Instant.now(),
  /** Original prompt used */
  prompt: String,
  /** Seed used for generation (if available) */
  seed: Option[Long] = None,
  /** Optional file path if saved to disk */
  filePath: Option[Path] = None,
  /** Optional URL if generated via URL method */
  url: Option[String] = None
) {

  /** Get the image data as bytes */
  def asBytes: Array[Byte] = {
    import java.util.Base64
    Base64.getDecoder.decode(data)
  }

  /** Save image to file and return updated GeneratedImage with file path */
  def saveToFile(path: Path): Either[ImageGenerationError, GeneratedImage] = {
    import java.nio.file.Files
    Try(Files.write(path, asBytes)).toEither.left
      .map(UnknownError.apply)
      .map(_ => copy(filePath = Some(path)))
  }
}

// ===== CONFIGURATION =====

/** Providers for image generation */
sealed trait ImageGenerationProvider

object ImageGenerationProvider {
  case object StableDiffusion extends ImageGenerationProvider
  case object DALLE           extends ImageGenerationProvider
  case object HuggingFace     extends ImageGenerationProvider
  case object StabilityAI     extends ImageGenerationProvider
}

trait ImageGenerationConfig {
  def provider: ImageGenerationProvider
  def model: String

  /** Request timeout */
  def timeout: FiniteDuration = 30.seconds
}

/** Configuration for Stable Diffusion */
case class StableDiffusionConfig(
  /** Base URL of the Stable Diffusion server (e.g., http://localhost:7860) */
  baseUrl: String = "http://localhost:7860",
  /** API key if required */
  apiKey: Option[String] = None,
  /** Model name (informational for Stable Diffusion web UI) */
  model: String = "stable-diffusion-v1-5",
  /** Request timeout */
  override val timeout: FiniteDuration = 60.seconds
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.StableDiffusion
  override def toString: String =
    s"StableDiffusionConfig(baseUrl=$baseUrl, apiKey=${apiKey.map(_ => "***")}, timeout=$timeout)"
}

/**
 * Configuration for the HuggingFace Inference API.
 *
 * @param apiKey Your HuggingFace API token. This is required for authentication.
 * @param model The identifier of the model to use on the HuggingFace Hub, e.g., "runwayml/stable-diffusion-v1-5".
 * @param timeout Request timeout. Defaults to a higher value suitable for cloud APIs.
 */
case class HuggingFaceConfig(
  /** HuggingFace API token */
  apiKey: String,
  /** Model to use (default: stable-diffusion-xl-base-1.0) */
  model: String = "stabilityai/stable-diffusion-xl-base-1.0",
  /** Request timeout */
  override val timeout: FiniteDuration = 2.minutes
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.HuggingFace
  override def toString: String         = s"HuggingFaceConfig(apiKey=***, model=$model, timeout=$timeout)"
}

/**
 * Configuration for OpenAI DALL-E API.
 *
 * @param apiKey Your OpenAI API key. This is required for authentication.
 * @param model The DALL-E model version to use (dall-e-2 or dall-e-3).
 * @param timeout Request timeout.
 */
case class OpenAIConfig(
  /** OpenAI API key */
  apiKey: String,
  /** Model to use (dall-e-2, dall-e-3, or gpt-image-1) */
  model: String = "dall-e-2",
  /** Base URL for OpenAI API */
  baseUrl: String = "https://api.openai.com/v1",
  /** Request timeout */
  override val timeout: FiniteDuration = 30.seconds
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.DALLE
  override def toString: String         = s"OpenAIConfig(apiKey=***, model=$model, baseUrl=$baseUrl, timeout=$timeout)"
}

/**
 * Configuration for Stability AI API.
 *
 * @param apiKey Your Stability AI API key. This is required for authentication.
 * @param model The engine/model ID to use (e.g., "stable-diffusion-xl-1024-v1-0", "stable-diffusion-v1-6").
 * @param baseUrl Base URL for Stability AI API (default: https://api.stability.ai).
 * @param timeout Request timeout.
 */
case class StabilityAIConfig(
  apiKey: String,
  model: String = "stable-diffusion-xl-1024-v1-0",
  baseUrl: String = "https://api.stability.ai",
  /** Request timeout */
  override val timeout: FiniteDuration = 2.minutes
) extends ImageGenerationConfig {
  def provider: ImageGenerationProvider = ImageGenerationProvider.StabilityAI
  override def toString: String = s"StabilityAIConfig(apiKey=***, model=$model, baseUrl=$baseUrl, timeout=$timeout)"
}

// ===== CLIENT INTERFACE =====

trait ImageGenerationClient {

  /** Generate an image from a text prompt */
  def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, GeneratedImage]

  /** Generate multiple images from a text prompt */
  def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, Seq[GeneratedImage]]

  /** Edit an existing image based on a prompt and optional mask */
  def editImage(
    @unused imagePath: Path,
    @unused prompt: String,
    @unused maskPath: Option[Path] = None,
    @unused options: ImageEditOptions = ImageEditOptions()
  ): Either[LLMError, Seq[GeneratedImage]] =
    Left(UnsupportedOperation("Image editing is not supported by this provider"))

  /** Generate an image asynchronously */
  def generateImageAsync(
    @unused prompt: String,
    @unused options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit @unused ec: ExecutionContext): Future[Either[LLMError, GeneratedImage]] =
    Future.successful(Left(UnsupportedOperation("Async generation is not supported by this provider")))

  /** Generate multiple images asynchronously */
  def generateImagesAsync(
    @unused prompt: String,
    @unused count: Int,
    @unused options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit @unused ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    Future.successful(Left(UnsupportedOperation("Async generation is not supported by this provider")))

  /** Edit an existing image asynchronously */
  def editImageAsync(
    @unused imagePath: Path,
    @unused prompt: String,
    @unused maskPath: Option[Path] = None,
    @unused options: ImageEditOptions = ImageEditOptions()
  )(implicit @unused ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    Future.successful(Left(UnsupportedOperation("Async editing is not supported by this provider")))

  /** Check the health/status of the image generation service */
  def health(): Either[LLMError, ServiceStatus] =
    Right(ServiceStatus(HealthStatus.Unknown, "Health check not implemented"))
}

// ===== FACTORY OBJECT =====

object ImageGeneration {

  /** Factory method for getting a client with the right configuration */
  def client(
    config: ImageGenerationConfig
  ): Either[LLMError, ImageGenerationClient] =
    createBaseClient(config)

  /** Factory method for getting an instrumented client with metrics and tracing */
  def client(
    config: ImageGenerationConfig,
    metrics: MetricsCollector,
    tracing: Tracing
  ): Either[LLMError, ImageGenerationClient] =
    createBaseClient(config).map(c => new InstrumentedImageGenerationClient(c, config, metrics, tracing))

  private def createBaseClient(
    config: ImageGenerationConfig
  ): Either[LLMError, ImageGenerationClient] = config match {
    case sdConfig: StableDiffusionConfig =>
      val httpClient = HttpClient.create()
      Right(new StableDiffusionClient(sdConfig, httpClient))
    case hfConfig: HuggingFaceConfig =>
      val httpClient = HttpClient.create()
      Right(new HuggingFaceClient(hfConfig, httpClient))
    case openAIConfig: OpenAIConfig =>
      val httpClient = HttpClient.create()
      Right(new OpenAIImageClient(openAIConfig, httpClient))
    case stabilityAIConfig: StabilityAIConfig =>
      val httpClient = HttpClient.create()
      Right(new StabilityAIClient(stabilityAIConfig, httpClient))
    case _ =>
      Left(UnsupportedOperation(s"Provider ${config.provider} is not supported."))
  }

  /** Convenience method for quick image generation */
  def generateImage(
    prompt: String,
    config: ImageGenerationConfig,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, GeneratedImage] =
    client(config).flatMap(_.generateImage(prompt, options))

  /** Convenience method for generating multiple images */
  def generateImages(
    prompt: String,
    count: Int,
    config: ImageGenerationConfig,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[LLMError, Seq[GeneratedImage]] =
    client(config).flatMap(_.generateImages(prompt, count, options))

  /** Convenience method for edit image */
  def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    config: ImageGenerationConfig,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[LLMError, Seq[GeneratedImage]] =
    client(config).flatMap(_.editImage(imagePath, prompt, maskPath, options))

  /** Convenience method for generating an image asynchronously */
  def generateImageAsync(
    prompt: String,
    config: ImageGenerationConfig,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, GeneratedImage]] =
    client(config) match {
      case Right(c) => c.generateImageAsync(prompt, options)
      case Left(e)  => Future.successful(Left(e))
    }

  /** Convenience method for generating multiple images asynchronously */
  def generateImagesAsync(
    prompt: String,
    count: Int,
    config: ImageGenerationConfig,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    client(config) match {
      case Right(c) => c.generateImagesAsync(prompt, count, options)
      case Left(e)  => Future.successful(Left(e))
    }

  /** Convenience method for editing an image asynchronously */
  def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    config: ImageGenerationConfig,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    client(config) match {
      case Right(c) => c.editImageAsync(imagePath, prompt, maskPath, options)
      case Left(e)  => Future.successful(Left(e))
    }

  /** Get a Stable Diffusion client with default local configuration */
  def stableDiffusionClient(
    baseUrl: String = "http://localhost:7860",
    apiKey: Option[String] = None
  ): Either[LLMError, ImageGenerationClient] = {
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
   * @return Either an error or an `ImageGenerationClient` instance configured for HuggingFace.
   */
  def huggingFaceClient(
    apiKey: String,
    model: String = "stabilityai/stable-diffusion-xl-base-1.0"
  ): Either[LLMError, ImageGenerationClient] = {
    val config = HuggingFaceConfig(apiKey = apiKey, model = model)
    client(config)
  }

  /**
   * Get an OpenAI client with the required API key.
   *
   * This is a convenience method for creating a client that connects to the
   * OpenAI API for image generation.
   *
   * @param apiKey Your OpenAI API key (required).
   * @param model The model version to use. Defaults to gpt-image-1.
   * @return Either an error or an `ImageGenerationClient` instance configured for OpenAI.
   */
  def openAIClient(
    apiKey: String,
    model: String = "dall-e-2"
  ): Either[LLMError, ImageGenerationClient] = {
    val config = OpenAIConfig(apiKey = apiKey, model = model)
    client(config)
  }

  /** Convenience method for quick Stable Diffusion image generation */
  def generateWithStableDiffusion(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions(),
    baseUrl: String = "http://localhost:7860"
  ): Either[LLMError, GeneratedImage] = {
    val config = StableDiffusionConfig(baseUrl = baseUrl)
    generateImage(prompt, config, options)
  }

  /** Convenience method for quick OpenAI image generation */
  def generateWithOpenAI(
    prompt: String,
    apiKey: String,
    options: ImageGenerationOptions = ImageGenerationOptions(),
    model: String = "dall-e-2"
  ): Either[LLMError, GeneratedImage] = {
    val config = OpenAIConfig(apiKey = apiKey, model = model)
    generateImage(prompt, config, options)
  }

  /**
   * Get a Stability AI client with the required API key.
   *
   * This is a convenience method for creating a client that connects to the
   * Stability AI API for image generation.
   *
   * @param apiKey Your Stability AI API key (required).
   * @param model The specific engine/model to use for generation. Defaults to stable-diffusion-xl-1024-v1-0.
   * @return Either an error or an `ImageGenerationClient` instance configured for Stability AI.
   */
  def stabilityAIClient(
    apiKey: String,
    model: String = "stable-diffusion-xl-1024-v1-0"
  ): Either[LLMError, ImageGenerationClient] = {
    val config = StabilityAIConfig(apiKey = apiKey, model = model)
    client(config)
  }

  /** Convenience method for quick Stability AI image generation */
  def generateWithStabilityAI(
    prompt: String,
    apiKey: String,
    options: ImageGenerationOptions = ImageGenerationOptions(),
    model: String = "stable-diffusion-xl-1024-v1-0"
  ): Either[LLMError, GeneratedImage] = {
    val config = StabilityAIConfig(apiKey = apiKey, model = model)
    generateImage(prompt, config, options)
  }

  /** Check service health */
  def healthCheck(config: ImageGenerationConfig): Either[LLMError, ServiceStatus] =
    client(config).flatMap(_.health())
}
