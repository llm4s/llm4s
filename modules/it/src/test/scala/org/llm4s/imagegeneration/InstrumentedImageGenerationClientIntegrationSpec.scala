package org.llm4s.imagegeneration

import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.metrics._
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Path
import scala.concurrent.duration._

/**
 * CI-safe integration tests for [[InstrumentedImageGenerationClient]].
 *
 * Uses a mock delegate client – no real HTTP calls are made. These tests
 * verify that:
 *  - Success metrics are incremented on a successful generation
 *  - Error metrics are incremented on a failed generation
 *  - Trace events are emitted with the correct fields
 *
 * These tests run under the standard `sbt test` (no API key required).
 */
class InstrumentedImageGenerationClientIntegrationSpec extends AnyFlatSpec with Matchers {

  // ─────────────────────────────────────────────────────────────
  // Test doubles
  // ─────────────────────────────────────────────────────────────

  private val mockImageData =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

  private class MockImageGenerationClient extends ImageGenerationClient {
    override def generateImage(
      prompt: String,
      options: ImageGenerationOptions
    ): Either[ImageGenerationError, GeneratedImage] =
      Right(
        GeneratedImage(
          data = mockImageData,
          format = options.format,
          size = options.size,
          prompt = prompt,
          seed = options.seed
        )
      )

    override def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions
    ): Either[ImageGenerationError, Seq[GeneratedImage]] =
      Right(
        (1 to count).map(i =>
          GeneratedImage(
            data = mockImageData,
            format = options.format,
            size = options.size,
            prompt = prompt,
            seed = options.seed.map(_ + i)
          )
        )
      )
  }

  private class FailingImageGenerationClient extends ImageGenerationClient {
    override def generateImage(
      prompt: String,
      options: ImageGenerationOptions
    ): Either[ImageGenerationError, GeneratedImage] =
      Left(ServiceError("Service unavailable", 503))

    override def generateImages(
      prompt: String,
      count: Int,
      options: ImageGenerationOptions
    ): Either[ImageGenerationError, Seq[GeneratedImage]] =
      Left(ServiceError("Service unavailable", 503))

    override def editImage(
      imagePath: Path,
      prompt: String,
      maskPath: Option[Path],
      options: ImageEditOptions
    ): Either[ImageGenerationError, Seq[GeneratedImage]] =
      Left(ServiceError("Service unavailable", 503))
  }

  private class CapturingMetricsCollector extends MetricsCollector {
    var imageGenCalls: List[(String, String, String, Outcome, FiniteDuration, Int)] = Nil
    var imageGenCostCalls: List[(String, String, Double, Int)]                       = Nil
    var costCalls: List[(String, String, Double)]                                    = Nil

    override def observeRequest(
      provider: String,
      model: String,
      outcome: Outcome,
      duration: FiniteDuration
    ): Unit = ()

    override def addTokens(
      provider: String,
      model: String,
      inputTokens: Long,
      outputTokens: Long
    ): Unit = ()

    override def recordCost(
      provider: String,
      model: String,
      costUsd: Double
    ): Unit =
      costCalls = costCalls :+ ((provider, model, costUsd))

    override def observeImageGeneration(
      provider: String,
      model: String,
      operation: String,
      outcome: Outcome,
      duration: FiniteDuration,
      imageCount: Int
    ): Unit =
      imageGenCalls = imageGenCalls :+ ((provider, model, operation, outcome, duration, imageCount))

    override def recordImageGenerationCost(
      provider: String,
      model: String,
      costUsd: Double,
      imageCount: Int
    ): Unit =
      imageGenCostCalls = imageGenCostCalls :+ ((provider, model, costUsd, imageCount))
  }

  private class CapturingTracing extends Tracing {
    var events: List[TraceEvent] = Nil

    override def traceEvent(event: TraceEvent): Result[Unit] = {
      events = events :+ event
      Right(())
    }

    override def traceAgentState(state: AgentState): Result[Unit]                                   = Right(())
    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    override def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    override def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  // ─────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────

  private def makeInstrumented(
    delegate: ImageGenerationClient,
    metrics: CapturingMetricsCollector,
    tracing: CapturingTracing,
    model: String = "dall-e-3"
  ): InstrumentedImageGenerationClient =
    new InstrumentedImageGenerationClient(
      delegate,
      OpenAIConfig(apiKey = "test-key", model = model),
      metrics,
      tracing
    )

  // ─────────────────────────────────────────────────────────────
  // Success path – metrics
  // ─────────────────────────────────────────────────────────────

  "InstrumentedImageGenerationClient" should "increment success metrics on successful generation" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new MockImageGenerationClient(), metrics, tracing)

    val result = client.generateImage("a blue circle")

    result.isRight shouldBe true
    metrics.imageGenCalls should have size 1

    val (provider, model, operation, outcome, _, imageCount) = metrics.imageGenCalls.head
    provider shouldBe "openai"
    model shouldBe "dall-e-3"
    operation shouldBe "generate"
    outcome shouldBe Outcome.Success
    imageCount shouldBe 1
  }

  it should "increment success metrics when generating multiple images" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new MockImageGenerationClient(), metrics, tracing)

    val result = client.generateImages("cats", 3)

    result.isRight shouldBe true
    metrics.imageGenCalls should have size 1

    val (_, _, _, outcome, _, imageCount) = metrics.imageGenCalls.head
    outcome shouldBe Outcome.Success
    imageCount shouldBe 3
  }

  // ─────────────────────────────────────────────────────────────
  // Failure path – metrics
  // ─────────────────────────────────────────────────────────────

  it should "increment error metrics on failed generation" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new FailingImageGenerationClient(), metrics, tracing)

    val result = client.generateImage("a blue circle")

    result.isLeft shouldBe true
    metrics.imageGenCalls should have size 1

    val (_, _, _, outcome, _, imageCount) = metrics.imageGenCalls.head
    outcome shouldBe Outcome.Error(ErrorKind.ServiceError)
    imageCount shouldBe 0
  }

  it should "increment error metrics on failed generateImages" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new FailingImageGenerationClient(), metrics, tracing)

    val result = client.generateImages("cats", 3)

    result.isLeft shouldBe true
    metrics.imageGenCalls should have size 1

    val (_, _, _, outcome, _, _) = metrics.imageGenCalls.head
    outcome shouldBe Outcome.Error(ErrorKind.ServiceError)
  }

  it should "increment error metrics on failed editImage" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new FailingImageGenerationClient(), metrics, tracing)

    val result = client.editImage(Path.of("image.png"), "make it brighter")

    result.isLeft shouldBe true
    metrics.imageGenCalls should have size 1

    val (_, _, operation, outcome, _, _) = metrics.imageGenCalls.head
    operation shouldBe "edit"
    outcome shouldBe Outcome.Error(ErrorKind.ServiceError)
  }

  // ─────────────────────────────────────────────────────────────
  // Trace events
  // ─────────────────────────────────────────────────────────────

  it should "emit ImageGenerationCompleted trace event with success=true on success" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new MockImageGenerationClient(), metrics, tracing)

    client.generateImage(
      "a blue circle",
      ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("standard"))
    )

    val imageEvents = tracing.events.collect { case e: TraceEvent.ImageGenerationCompleted => e }
    imageEvents should have size 1

    val event = imageEvents.head
    event.provider shouldBe "openai"
    event.model shouldBe "dall-e-3"
    event.operation shouldBe "generate"
    event.imageCount shouldBe 1
    event.success shouldBe true
    event.errorMessage shouldBe None
  }

  it should "emit ImageGenerationCompleted trace event with success=false and error message on failure" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new FailingImageGenerationClient(), metrics, tracing)

    client.generateImage("a blue circle")

    val imageEvents = tracing.events.collect { case e: TraceEvent.ImageGenerationCompleted => e }
    imageEvents should have size 1

    val event = imageEvents.head
    event.success shouldBe false
    event.errorMessage shouldBe Some("Service unavailable")
    event.imageCount shouldBe 0
  }

  // ─────────────────────────────────────────────────────────────
  // Metric duration – basic sanity check
  // ─────────────────────────────────────────────────────────────

  it should "record a non-negative duration for the image generation call" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client  = makeInstrumented(new MockImageGenerationClient(), metrics, tracing)

    client.generateImage("a blue circle")

    metrics.imageGenCalls should have size 1

    val (_, _, _, _, duration, _) = metrics.imageGenCalls.head
    duration.toMillis should be >= 0L
  }

  // ─────────────────────────────────────────────────────────────
  // Provider name mapping
  // ─────────────────────────────────────────────────────────────

  it should "use stable-diffusion as provider name for StableDiffusionConfig" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client = new InstrumentedImageGenerationClient(
      new MockImageGenerationClient(),
      StableDiffusionConfig(),
      metrics,
      tracing
    )

    client.generateImage("a red square")

    metrics.imageGenCalls should have size 1
    val (provider, _, _, _, _, _) = metrics.imageGenCalls.head
    provider shouldBe "stable-diffusion"
  }

  it should "use stability-ai as provider name for StabilityAIConfig" in {
    val metrics = new CapturingMetricsCollector()
    val tracing = new CapturingTracing()
    val client = new InstrumentedImageGenerationClient(
      new MockImageGenerationClient(),
      StabilityAIConfig(apiKey = "test-key"),
      metrics,
      tracing
    )

    client.generateImage("a red square")

    metrics.imageGenCalls should have size 1
    val (provider, _, _, _, _, _) = metrics.imageGenCalls.head
    provider shouldBe "stability-ai"
  }
}
