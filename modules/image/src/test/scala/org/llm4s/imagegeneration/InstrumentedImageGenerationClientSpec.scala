package org.llm4s.imagegeneration

import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.media.MediaType
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.trace.{ Tracing, TraceEvent }
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._

import java.nio.file.{ Path, Paths }
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.concurrent.duration._

class InstrumentedImageGenerationClientSpec extends AnyFunSuite with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val testConfig = new ImageGenerationConfig {
    def provider: ImageGenerationProvider = ImageGenerationProvider.DALLE
    def model: String                     = "dall-e-3"
  }

  private val image = GeneratedImage(
    data = "ZmFrZQ==",
    format = MediaType.Png,
    size = ImageSize.Square1024,
    prompt = "a cat"
  )

  private val failure = ImageServiceError("provider is down", 503)

  private val healthy = ServiceStatus(HealthStatus.Healthy, "ok")

  private class RecordingMetricsCollector extends MetricsCollector {
    val imageGenerationCalls: ListBuffer[(String, String, String, Outcome, FiniteDuration, Int)] = ListBuffer.empty

    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      ()
    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = ()
    override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = ()
    override def observeImageGeneration(
      provider: String,
      model: String,
      operation: String,
      outcome: Outcome,
      duration: FiniteDuration,
      imageCount: Int
    ): Unit =
      imageGenerationCalls += ((provider, model, operation, outcome, duration, imageCount))
  }

  private class RecordingTracing extends Tracing {
    val events: ListBuffer[TraceEvent] = ListBuffer.empty

    override def traceEvent(event: TraceEvent): Result[Unit] = {
      events += event
      Right(())
    }
    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    override def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    override def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  private class StubDelegate(
    imageResult: Either[LLMError, GeneratedImage] = Right(image),
    imagesResult: Either[LLMError, Seq[GeneratedImage]] = Right(Seq(image)),
    healthResult: Either[LLMError, ServiceStatus] = Right(healthy)
  ) extends ImageGenerationClient {
    override def generateImage(
      prompt: String,
      options: ImageGenerationOptions
    ): Either[LLMError, GeneratedImage] = imageResult

    override def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions
    ): Either[LLMError, Seq[GeneratedImage]] = imagesResult

    override def editImage(
      imagePath: Path,
      prompt: String,
      maskPath: Option[Path],
      options: ImageEditOptions
    ): Either[LLMError, Seq[GeneratedImage]] = imagesResult

    override def generateImageAsync(
      prompt: String,
      options: ImageGenerationOptions
    )(implicit ec: ExecutionContext): Future[Either[LLMError, GeneratedImage]] =
      Future.successful(imageResult)

    override def generateImagesAsync(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions
    )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
      Future.successful(imagesResult)

    override def editImageAsync(
      imagePath: Path,
      prompt: String,
      maskPath: Option[Path],
      options: ImageEditOptions
    )(implicit ec: ExecutionContext): Future[Either[LLMError, Seq[GeneratedImage]]] =
      Future.successful(imagesResult)

    override def health(): Either[LLMError, ServiceStatus] = healthResult
  }

  test("generateImage delegates, records success metrics and a trace event") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result = client.generateImage("a cat", ImageGenerationOptions())

    result shouldBe Right(image)
    metrics.imageGenerationCalls should have size 1
    val (provider, model, operation, outcome, _, imageCount) = metrics.imageGenerationCalls.head
    provider shouldBe "openai"
    model shouldBe "dall-e-3"
    operation shouldBe "generate"
    outcome shouldBe Outcome.Success
    imageCount shouldBe 1
    tracing.events should have size 1
    tracing.events.head shouldBe a[TraceEvent.ImageGenerationCompleted]
  }

  test("generateImage records failure metrics and a failed trace event") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client =
      new InstrumentedImageGenerationClient(new StubDelegate(imageResult = Left(failure)), testConfig, metrics, tracing)

    val result = client.generateImage("a cat", ImageGenerationOptions())

    result shouldBe Left(failure)
    metrics.imageGenerationCalls.head._4 shouldBe Outcome.Error(ErrorKind.ServiceError)
    val event = tracing.events.head.asInstanceOf[TraceEvent.ImageGenerationCompleted]
    event.success shouldBe false
    event.errorMessage.value shouldBe failure.message
  }

  test("generateImage records a cancelled call as cancelled, not as an unknown failure") {
    val metrics   = new RecordingMetricsCollector()
    val tracing   = new RecordingTracing()
    val cancelled = CancelledError("test.generate")
    val client =
      new InstrumentedImageGenerationClient(
        new StubDelegate(imageResult = Left(cancelled)),
        testConfig,
        metrics,
        tracing
      )

    client.generateImage("a cat", ImageGenerationOptions()) shouldBe Left(cancelled)

    metrics.imageGenerationCalls.head._4 shouldBe Outcome.Error(ErrorKind.Cancelled)
  }

  test("generateImage records an error of another kind under that error's own kind") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val other   = org.llm4s.error.ConfigurationError("no API key is configured")
    val client =
      new InstrumentedImageGenerationClient(new StubDelegate(imageResult = Left(other)), testConfig, metrics, tracing)

    client.generateImage("a cat", ImageGenerationOptions()) shouldBe Left(other)

    metrics.imageGenerationCalls.head._4 shouldBe Outcome.Error(ErrorKind.fromLLMError(other))
    metrics.imageGenerationCalls.head._4 should not be Outcome.Error(ErrorKind.Unknown)
  }

  test("generateImages delegates and records metrics for every generated image") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result = client.generateImages("a cat", 2, ImageGenerationOptions())

    result shouldBe Right(Seq(image))
    metrics.imageGenerationCalls.head._6 shouldBe 1
  }

  test("editImage delegates and records an edit operation") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result = client.editImage(Paths.get("image.png"), "add a hat", None, ImageEditOptions())

    result shouldBe Right(Seq(image))
    metrics.imageGenerationCalls.head._3 shouldBe "edit"
  }

  test("generateImageAsync delegates and records metrics after the future completes") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result = Await.result(client.generateImageAsync("a cat", ImageGenerationOptions()), 5.seconds)

    result shouldBe Right(image)
    metrics.imageGenerationCalls should have size 1
    tracing.events should have size 1
  }

  test("generateImagesAsync delegates and records metrics after the future completes") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result = Await.result(client.generateImagesAsync("a cat", 2, ImageGenerationOptions()), 5.seconds)

    result shouldBe Right(Seq(image))
    metrics.imageGenerationCalls.head._3 shouldBe "generate"
  }

  test("editImageAsync delegates and records an edit operation after the future completes") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result =
      Await.result(client.editImageAsync(Paths.get("image.png"), "add a hat", None, ImageEditOptions()), 5.seconds)

    result shouldBe Right(Seq(image))
    metrics.imageGenerationCalls.head._3 shouldBe "edit"
  }

  test("editImageAsync records failure metrics after the future completes") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client = new InstrumentedImageGenerationClient(
      new StubDelegate(imagesResult = Left(failure)),
      testConfig,
      metrics,
      tracing
    )

    val result =
      Await.result(client.editImageAsync(Paths.get("image.png"), "add a hat", None, ImageEditOptions()), 5.seconds)

    result shouldBe Left(failure)
    metrics.imageGenerationCalls.head._4 shouldBe Outcome.Error(ErrorKind.ServiceError)
  }

  test("maps each provider config to its metrics provider name") {
    val cases: Seq[(ImageGenerationConfig, String)] = Seq(
      StableDiffusionConfig()                  -> "stable-diffusion",
      StabilityAIConfig(apiKey = "test-key")   -> "stability-ai",
      HuggingFaceConfig(apiKey = "test-token") -> "huggingface",
      testConfig                               -> "openai"
    )

    cases.foreach { case (config, expected) =>
      val metrics = new RecordingMetricsCollector()
      val client  = new InstrumentedImageGenerationClient(new StubDelegate(), config, metrics, new RecordingTracing())

      client.generateImage("a red square", ImageGenerationOptions())

      metrics.imageGenerationCalls.map(_._1) shouldBe Seq(expected)
    }
  }

  test("health delegates directly without recording metrics or trace events") {
    val metrics = new RecordingMetricsCollector()
    val tracing = new RecordingTracing()
    val client  = new InstrumentedImageGenerationClient(new StubDelegate(), testConfig, metrics, tracing)

    val result = client.health()

    result shouldBe Right(healthy)
    metrics.imageGenerationCalls shouldBe empty
    tracing.events shouldBe empty
  }
}
