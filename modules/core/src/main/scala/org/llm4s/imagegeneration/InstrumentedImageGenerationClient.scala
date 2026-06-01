package org.llm4s.imagegeneration

import org.llm4s.metrics.{ MetricsCollector, Outcome, ErrorKind }
import org.llm4s.trace.{ Tracing, TraceEvent }

import java.nio.file.Path
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._

/**
 * Decorator that wraps an [[ImageGenerationClient]] with metrics collection
 * and trace event emission.
 *
 * Records:
 *  - `observeImageGeneration` for every generate/edit call (success or failure)
 *  - `recordImageGenerationCost` when pricing is available via [[ImagePricingRegistry]]
 *  - `TraceEvent.ImageGenerationCompleted` for observability
 *
 * Note: Cost estimation uses the requested size, not the actual provider-billed
 * dimensions. For models like dall-e-3 that normalize sizes (e.g., mapping
 * non-standard requested sizes to their nearest supported size), the estimate
 * may differ from actual billing. When size is unknown (e.g., edit operations
 * without an explicit size), cost estimation is skipped.
 *
 * @param delegate   The underlying client to delegate actual generation to
 * @param config     Image generation configuration (provides model/provider info)
 * @param metrics    Metrics collector for Prometheus counters/histograms
 * @param tracing    Tracing backend for structured event emission
 */
class InstrumentedImageGenerationClient(
  delegate: ImageGenerationClient,
  config: ImageGenerationConfig,
  metrics: MetricsCollector,
  tracing: Tracing
) extends ImageGenerationClient {

  private val providerName: String = config.provider match {
    case ImageGenerationProvider.StableDiffusion => "stable-diffusion"
    case ImageGenerationProvider.DALLE           => "openai"
    case ImageGenerationProvider.HuggingFace     => "huggingface"
    case ImageGenerationProvider.StabilityAI     => "stability-ai"
  }

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, GeneratedImage] = {
    val startNanos = System.nanoTime()
    val result     = delegate.generateImage(prompt, options)
    val duration   = Duration.fromNanos(System.nanoTime() - startNanos).toMillis

    recordMetricsAndTrace("generate", result.map(Seq(_)), Some(options.size), options.quality, duration)
    result
  }

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val startNanos = System.nanoTime()
    val result     = delegate.generateImages(prompt, count, options)
    val duration   = Duration.fromNanos(System.nanoTime() - startNanos).toMillis

    recordMetricsAndTrace("generate", result, Some(options.size), options.quality, duration)
    result
  }

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path],
    options: ImageEditOptions
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val startNanos = System.nanoTime()
    val result     = delegate.editImage(imagePath, prompt, maskPath, options)
    val duration   = Duration.fromNanos(System.nanoTime() - startNanos).toMillis

    recordMetricsAndTrace("edit", result, options.size, None, duration)
    result
  }

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] = {
    val startNanos = System.nanoTime()
    delegate.generateImageAsync(prompt, options).map { result =>
      val duration = Duration.fromNanos(System.nanoTime() - startNanos).toMillis
      recordMetricsAndTrace("generate", result.map(Seq(_)), Some(options.size), options.quality, duration)
      result
    }
  }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] = {
    val startNanos = System.nanoTime()
    delegate.generateImagesAsync(prompt, count, options).map { result =>
      val duration = Duration.fromNanos(System.nanoTime() - startNanos).toMillis
      recordMetricsAndTrace("generate", result, Some(options.size), options.quality, duration)
      result
    }
  }

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path],
    options: ImageEditOptions
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] = {
    val startNanos = System.nanoTime()
    delegate.editImageAsync(imagePath, prompt, maskPath, options).map { result =>
      val duration = Duration.fromNanos(System.nanoTime() - startNanos).toMillis
      recordMetricsAndTrace("edit", result, options.size, None, duration)
      result
    }
  }

  override def health(): Either[ImageGenerationError, ServiceStatus] =
    delegate.health()

  private def errorKindFromImageError(err: ImageGenerationError): ErrorKind = err match {
    case _: AuthenticationError        => ErrorKind.Authentication
    case _: RateLimitError             => ErrorKind.RateLimit
    case _: ServiceError               => ErrorKind.ServiceError
    case _: ValidationError            => ErrorKind.Validation
    case _: InvalidPromptError         => ErrorKind.Validation
    case _: InsufficientResourcesError => ErrorKind.ServiceError
    case _: UnsupportedOperation       => ErrorKind.Validation
    case _: UnknownError               => ErrorKind.Unknown
  }

  private def recordMetricsAndTrace(
    operation: String,
    result: Either[ImageGenerationError, Seq[GeneratedImage]],
    size: Option[ImageSize],
    quality: Option[String],
    durationMs: Long
  ): Unit = {
    val durationFD = FiniteDuration(durationMs, MILLISECONDS)
    val qualityStr = quality.getOrElse("standard")
    val sizeStr    = size.map(_.description).getOrElse("unknown")
    val model      = config.model
    val imageCount = result.map(_.size).getOrElse(0)

    val outcome = result match {
      case Right(_)  => Outcome.Success
      case Left(err) => Outcome.Error(errorKindFromImageError(err))
    }

    metrics.observeImageGeneration(providerName, model, operation, outcome, durationFD, imageCount)

    // Cost estimation uses the requested size, not the actual provider-billed dimensions.
    // For models like dall-e-3 that normalize sizes, the estimate may differ from actual billing.
    // When size is unknown (e.g., edit without explicit size), cost estimation is skipped.
    val costUsd = result match {
      case Right(_) =>
        size.flatMap { s =>
          val cost = ImagePricingRegistry.estimateCost(model, quality, s, imageCount)
          cost.foreach { c =>
            metrics.recordImageGenerationCost(providerName, model, c, imageCount)
            metrics.recordCost(providerName, model, c)
          }
          cost
        }
      case Left(_) => None
    }

    val event = TraceEvent.ImageGenerationCompleted(
      model = model,
      provider = providerName,
      operation = operation,
      imageCount = imageCount,
      size = sizeStr,
      quality = qualityStr,
      durationMs = durationMs,
      costUsd = costUsd,
      success = result.isRight,
      errorMessage = result.left.toOption.map(_.message)
    )
    tracing.traceEvent(event)
  }
}
