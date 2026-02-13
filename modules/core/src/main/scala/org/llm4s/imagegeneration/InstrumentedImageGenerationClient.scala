package org.llm4s.imagegeneration

import org.llm4s.metrics._
import org.llm4s.trace.Tracing
import java.nio.file.Path

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration._
import scala.util.{ Failure, Success }

/**
 * A wrapper around ImageGenerationClient that records metrics and traces.
 *
 * @param underlying The actual client implementation
 * @param metrics The metrics collector
 * @param tracing The tracing service
 * @param provider The provider name (e.g. "openai", "stability")
 * @param model The model name
 */
class InstrumentedImageGenerationClient(
  val underlying: ImageGenerationClient,
  metrics: MetricsCollector,
  tracing: Tracing,
  provider: String,
  model: String
) extends ImageGenerationClient {

  override def generateImage(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, GeneratedImage] = {
    val start  = System.nanoTime()
    val result = underlying.generateImage(prompt, options)
    recordMetrics(start, 1, Some(options.size), result)
    result
  }

  override def generateImages(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val start  = System.nanoTime()
    val result = underlying.generateImages(prompt, count, options)
    recordMetrics(start, count, Some(options.size), result)
    result
  }

  override def editImage(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  ): Either[ImageGenerationError, Seq[GeneratedImage]] = {
    val start  = System.nanoTime()
    val result = underlying.editImage(imagePath, prompt, maskPath, options)
    recordMetrics(start, options.n, options.size, result, "image_edit")
    result
  }

  override def generateImageAsync(
    prompt: String,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, GeneratedImage]] = {
    val start  = System.nanoTime()
    val future = underlying.generateImageAsync(prompt, options)
    future.onComplete {
      case Success(result) => recordMetrics(start, 1, Some(options.size), result)
      case Failure(exception) =>
        val duration = FiniteDuration(System.nanoTime() - start, NANOSECONDS)
        metrics.observeRequest(provider, model, Outcome.Error(ErrorKind.Unknown), duration)
        tracing.traceError(exception, "generateImageAsync")
    }
    future
  }

  override def generateImagesAsync(
    prompt: String,
    count: Int,
    options: ImageGenerationOptions = ImageGenerationOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] = {
    val start  = System.nanoTime()
    val future = underlying.generateImagesAsync(prompt, count, options)
    future.onComplete {
      case Success(result) => recordMetrics(start, count, Some(options.size), result)
      case Failure(exception) =>
        val duration = FiniteDuration(System.nanoTime() - start, NANOSECONDS)
        metrics.observeRequest(provider, model, Outcome.Error(ErrorKind.Unknown), duration)
        tracing.traceError(exception, "generateImagesAsync")
    }
    future
  }

  override def editImageAsync(
    imagePath: Path,
    prompt: String,
    maskPath: Option[Path] = None,
    options: ImageEditOptions = ImageEditOptions()
  )(implicit ec: ExecutionContext): Future[Either[ImageGenerationError, Seq[GeneratedImage]]] = {
    val start  = System.nanoTime()
    val future = underlying.editImageAsync(imagePath, prompt, maskPath, options)
    future.onComplete {
      case Success(result) => recordMetrics(start, options.n, options.size, result, "image_edit")
      case Failure(exception) =>
        val duration = FiniteDuration(System.nanoTime() - start, NANOSECONDS)
        metrics.observeRequest(provider, model, Outcome.Error(ErrorKind.Unknown), duration)
        tracing.traceError(exception, "editImageAsync")
    }
    future
  }

  override def health(): Either[ImageGenerationError, ServiceStatus] = underlying.health()

  private def recordMetrics[T](
    startNanos: Long,
    count: Int,
    requestSize: Option[ImageSize],
    result: Either[ImageGenerationError, T],
    operation: String = "image_generation"
  ): Unit = {
    val duration = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)

    result match {
      case Right(data) =>
        // Derive the actual size from the response if possible, otherwise fallback to request size or default
        val actualSize = data match {
          case img: GeneratedImage => Some(img.size)
          case seq: Seq[?] if seq.nonEmpty && seq.head.isInstanceOf[GeneratedImage] =>
            Some(seq.head.asInstanceOf[GeneratedImage].size)
          case _ => requestSize
        }

        val finalSize = actualSize.getOrElse(ImageSize.Square1024) // Final fallback for billing
        val cost      = ImagePricingRegistry.estimateCost(provider, model, count, finalSize.description)
        metrics.recordImageGeneration(provider, model, Outcome.Success, count, cost, duration)
        tracing.traceImageGeneration(
          costUsd = cost,
          provider = provider,
          model = model,
          operation = operation,
          imageCount = count,
          imageSize = finalSize.description,
          durationMs = duration.toMillis
        )

      case Left(error) =>
        // Convert ImageGenerationError to metrics ErrorKind
        val errorKind = error match {
          case _: RateLimitError      => ErrorKind.RateLimit
          case _: ServiceError        => ErrorKind.Network
          case _: AuthenticationError => ErrorKind.Authentication
          case _: ValidationError     => ErrorKind.Validation
          case _: InvalidPromptError  => ErrorKind.Validation
          case _                      => ErrorKind.Unknown
        }

        metrics.observeRequest(provider, model, Outcome.Error(errorKind), duration)
        tracing.traceError(new RuntimeException(error.message), operation)
    }
  }
}
