package org.llm4s.imagegeneration

import org.llm4s.error.{ CancelledError, LLMError }
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
  ): Either[LLMError, GeneratedImage] = {
    val (result, duration) = timed(delegate.generateImage(prompt, options))
    recordMetricsAndTrace("generate", result.map(Seq(_)), Some(options.size), options.quality, duration)
    result
  }

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  ): Either[LLMError, Seq[GeneratedImage]] = {
    val (result, duration) = timed(delegate.generateImages(prompt, count, options))
    recordMetricsAndTrace("generate", result, Some(options.size), options.quality, duration)
    result
  }

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path],
    options: ImageEditOptions
  ): Either[LLMError, Seq[GeneratedImage]] = {
    val (result, duration) = timed(delegate.editImage(imagePath, prompt, maskPath, options))
    recordMetricsAndTrace("edit", result, options.size, None, duration)
    result
  }

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions
  )(implicit ec: ExecutionContext): Future[Either[LLMError, GeneratedImage]] =
    timedAsync(delegate.generateImageAsync(prompt, options)).map { case (result, duration) =>
      recordMetricsAndTrace("generate", result.map(Seq(_)), Some(options.size), options.quality, duration)
      result
    }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    timedAsync(delegate.generateImagesAsync(prompt, count, options)).map { case (result, duration) =>
      recordMetricsAndTrace("generate", result, Some(options.size), options.quality, duration)
      result
    }

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path],
    options: ImageEditOptions
  )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
    timedAsync(delegate.editImageAsync(imagePath, prompt, maskPath, options)).map { case (result, duration) =>
      recordMetricsAndTrace("edit", result, options.size, None, duration)
      result
    }

  override def health(): Either[LLMError, ServiceStatus] =
    delegate.health()

  /** Runs `operation`, pairing its result with the elapsed wall time. */
  private def timed[A](operation: => A): (A, FiniteDuration) = {
    val startNanos = System.nanoTime()
    val result     = operation
    (result, Duration.fromNanos(System.nanoTime() - startNanos))
  }

  /** Runs a `Future`-producing `operation`, pairing its result with the elapsed wall time. */
  private def timedAsync[A](operation: => Future[A])(implicit ec: ExecutionContext): Future[(A, FiniteDuration)] = {
    val startNanos = System.nanoTime()
    operation.map(result => (result, Duration.fromNanos(System.nanoTime() - startNanos)))
  }

  private def errorKindFromImageError(err: LLMError): ErrorKind = err match {
    case _: CancelledError             => ErrorKind.Cancelled
    case _: AuthenticationError        => ErrorKind.Authentication
    case _: RateLimitError             => ErrorKind.RateLimit
    case _: ServiceError               => ErrorKind.ServiceError
    case _: ValidationError            => ErrorKind.Validation
    case _: InvalidPromptError         => ErrorKind.Validation
    case _: InsufficientResourcesError => ErrorKind.ServiceError
    case _: UnsupportedOperation       => ErrorKind.Validation
    case _: UnknownError               => ErrorKind.Unknown
    case other                         => ErrorKind.fromLLMError(other)
  }

  private def recordMetricsAndTrace(
    operation: String,
    result: Either[LLMError, Seq[GeneratedImage]],
    size: Option[ImageSize],
    quality: Option[String],
    duration: FiniteDuration
  ): Unit = {
    val qualityStr = quality.getOrElse("standard")
    val sizeStr    = size.map(_.description).getOrElse("unknown")
    val model      = config.model
    val imageCount = result.map(_.size).getOrElse(0)

    val outcome = result match {
      case Right(_)  => Outcome.Success
      case Left(err) => Outcome.Error(errorKindFromImageError(err))
    }

    metrics.observeImageGeneration(providerName, model, operation, outcome, duration, imageCount)

    // Cost estimation uses the requested size, not the actual provider-billed dimensions.
    // For models like dall-e-3 that normalize sizes, the estimate may differ from actual billing.
    // When size is unknown (e.g., edit without explicit size), cost estimation is skipped.
    val costUsd = result match {
      case Right(_) =>
        size.flatMap { s =>
          val cost = ImagePricingRegistry.estimateCost(model, quality, s, imageCount)
          cost.foreach { c =>
            // Record to both the image-specific cost counter (for a per-provider/model image
            // breakdown) and the unified cost counter (so total spend across all operations,
            // LLM and image, stays correct). This dual recording is intentional, not double
            // counting within a single series.
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
      duration = duration,
      costUsd = costUsd,
      success = result.isRight,
      errorMessage = result.left.toOption.map(_.message)
    )
    tracing.traceEvent(event)
  }
}
