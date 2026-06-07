package org.llm4s.imagegeneration

import org.llm4s.metrics._
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.agent.AgentState
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.types.Result
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

class ImageGenerationCostTrackingSpec extends AnyFlatSpec with Matchers {

  private val mockImageData =
    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="

  class MockImageClient extends ImageGenerationClient {
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

  class FailingImageClient extends ImageGenerationClient {
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
  }

  class TracingCollector extends Tracing {
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
  // TraceEvent.ImageGenerationCompleted
  // ─────────────────────────────────────────────────────────────

  "TraceEvent.ImageGenerationCompleted" should "serialize to JSON correctly" in {
    val event = TraceEvent.ImageGenerationCompleted(
      model = "dall-e-3",
      provider = "openai",
      operation = "generate",
      imageCount = 2,
      size = "1024x1024",
      quality = "hd",
      durationMs = 3500,
      costUsd = Some(0.160),
      success = true
    )

    event.eventType shouldBe "image_generation_completed"
    val json = event.toJson
    json("event_type").str shouldBe "image_generation_completed"
    json("model").str shouldBe "dall-e-3"
    json("provider").str shouldBe "openai"
    json("operation").str shouldBe "generate"
    json("image_count").num shouldBe 2.0
    json("size").str shouldBe "1024x1024"
    json("quality").str shouldBe "hd"
    json("duration_ms").num shouldBe 3500.0
    json("cost_usd").num shouldBe 0.160 +- 0.001
    json("success").bool shouldBe true
  }

  it should "omit optional fields when None" in {
    val event = TraceEvent.ImageGenerationCompleted(
      model = "dall-e-3",
      provider = "openai",
      operation = "generate",
      imageCount = 1,
      size = "1024x1024",
      quality = "standard",
      durationMs = 2000
    )

    val json = event.toJson
    json.obj.contains("cost_usd") shouldBe false
    json.obj.contains("error_message") shouldBe false
  }

  it should "include error message on failure" in {
    val event = TraceEvent.ImageGenerationCompleted(
      model = "dall-e-3",
      provider = "openai",
      operation = "generate",
      imageCount = 0,
      size = "1024x1024",
      quality = "standard",
      durationMs = 500,
      success = false,
      errorMessage = Some("Service unavailable")
    )

    val json = event.toJson
    json("success").bool shouldBe false
    json("error_message").str shouldBe "Service unavailable"
  }

  // ─────────────────────────────────────────────────────────────
  // InstrumentedImageGenerationClient - metrics recording
  // ─────────────────────────────────────────────────────────────

  "InstrumentedImageGenerationClient" should "record metrics on successful generation" in {
    val prometheusMetrics = PrometheusMetrics.create()
    val tracing           = new TracingCollector()
    val config            = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new MockImageClient(),
      config,
      prometheusMetrics,
      tracing
    )

    val result = instrumented.generateImage(
      "A cat",
      ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("standard"))
    )
    result.isRight shouldBe true

    val imageGenCount = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_image_generations_total",
      Map("provider" -> "openai", "model" -> "dall-e-3", "operation" -> "generate", "status" -> "success")
    )
    imageGenCount shouldBe 1.0

    val imagesCount = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_images_generated_total",
      Map("provider" -> "openai", "model" -> "dall-e-3")
    )
    imagesCount shouldBe 1.0
  }

  it should "record cost metrics when pricing is available" in {
    val prometheusMetrics = PrometheusMetrics.create()
    val tracing           = new TracingCollector()
    val config            = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new MockImageClient(),
      config,
      prometheusMetrics,
      tracing
    )

    instrumented.generateImage("A cat", ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("standard")))

    val imageCost = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_image_generation_cost_usd_total",
      Map("provider" -> "openai", "model" -> "dall-e-3")
    )
    imageCost shouldBe 0.040 +- 0.001
  }

  it should "record metrics on failed generation" in {
    val prometheusMetrics = PrometheusMetrics.create()
    val tracing           = new TracingCollector()
    val config            = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new FailingImageClient(),
      config,
      prometheusMetrics,
      tracing
    )

    val result = instrumented.generateImage("A cat")
    result.isLeft shouldBe true

    val errorCount = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_image_generations_total",
      Map("provider" -> "openai", "model" -> "dall-e-3", "operation" -> "generate", "status" -> "error_service_error")
    )
    errorCount shouldBe 1.0
  }

  it should "record image generation error counter on failure" in {
    val prometheusMetrics = PrometheusMetrics.create()
    val tracing           = new TracingCollector()
    val config            = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new FailingImageClient(),
      config,
      prometheusMetrics,
      tracing
    )

    instrumented.generateImage("A cat")

    val imageGenErrorCount = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_image_generation_errors_total",
      Map("provider" -> "openai", "model" -> "dall-e-3", "operation" -> "generate", "error_type" -> "service_error")
    )
    imageGenErrorCount shouldBe 1.0
  }

  it should "record duration histogram" in {
    val prometheusMetrics = PrometheusMetrics.create()
    val tracing           = new TracingCollector()
    val config            = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new MockImageClient(),
      config,
      prometheusMetrics,
      tracing
    )

    instrumented.generateImage("A cat")

    val histogramCount = getHistogramCount(
      prometheusMetrics.registry,
      "llm4s_image_generation_duration_seconds",
      Map("provider" -> "openai", "model" -> "dall-e-3", "operation" -> "generate")
    )
    histogramCount shouldBe 1.0
  }

  it should "record multiple images count correctly" in {
    val prometheusMetrics = PrometheusMetrics.create()
    val tracing           = new TracingCollector()
    val config            = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new MockImageClient(),
      config,
      prometheusMetrics,
      tracing
    )

    instrumented.generateImages(
      "Dogs",
      3,
      ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("standard"))
    )

    val imagesCount = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_images_generated_total",
      Map("provider" -> "openai", "model" -> "dall-e-3")
    )
    imagesCount shouldBe 3.0

    val imageCost = getMetricValue(
      prometheusMetrics.registry,
      "llm4s_image_generation_cost_usd_total",
      Map("provider" -> "openai", "model" -> "dall-e-3")
    )
    imageCost shouldBe 0.120 +- 0.001
  }

  // ─────────────────────────────────────────────────────────────
  // InstrumentedImageGenerationClient - trace events
  // ─────────────────────────────────────────────────────────────

  it should "emit ImageGenerationCompleted trace event on success" in {
    val tracing = new TracingCollector()
    val config  = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new MockImageClient(),
      config,
      MetricsCollector.noop,
      tracing
    )

    instrumented.generateImage("A cat", ImageGenerationOptions(size = ImageSize.Square1024, quality = Some("standard")))

    val imageEvents = tracing.events.collect { case e: TraceEvent.ImageGenerationCompleted => e }
    imageEvents should have size 1

    val event = imageEvents.head
    event.model shouldBe "dall-e-3"
    event.provider shouldBe "openai"
    event.operation shouldBe "generate"
    event.imageCount shouldBe 1
    event.success shouldBe true
    event.costUsd shouldBe Some(0.040)
  }

  it should "emit error trace event on failure" in {
    val tracing = new TracingCollector()
    val config  = OpenAIConfig(apiKey = "test-key", model = "dall-e-3")
    val instrumented = new InstrumentedImageGenerationClient(
      new FailingImageClient(),
      config,
      MetricsCollector.noop,
      tracing
    )

    instrumented.generateImage("A cat")

    val imageEvents = tracing.events.collect { case e: TraceEvent.ImageGenerationCompleted => e }
    imageEvents should have size 1

    val event = imageEvents.head
    event.success shouldBe false
    event.errorMessage shouldBe Some("Service unavailable")
    event.costUsd shouldBe None
  }

  // ─────────────────────────────────────────────────────────────
  // MetricsCollector.noop compatibility
  // ─────────────────────────────────────────────────────────────

  "MetricsCollector.noop" should "not throw on image generation methods" in {
    val metrics = MetricsCollector.noop

    noException should be thrownBy {
      metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 1.second, 1)
      metrics.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)
      metrics.observeImageGeneration(
        "openai",
        "dall-e-3",
        "generate",
        Outcome.Error(ErrorKind.ServiceError),
        500.millis,
        0
      )
    }
  }

  // ─────────────────────────────────────────────────────────────
  // ImageGeneration factory with instrumentation
  // ─────────────────────────────────────────────────────────────

  "ImageGeneration.client with metrics and tracing" should "return an instrumented client" in {
    val tracing = new TracingCollector()
    val metrics = PrometheusMetrics.create()
    val config  = StableDiffusionConfig()

    val result = ImageGeneration.client(config, metrics, tracing)
    result.isRight shouldBe true
  }

  "ImageGeneration.client without args" should "still work (backward compatible)" in {
    val result = ImageGeneration.client(StableDiffusionConfig())
    result.isRight shouldBe true
  }

  // ─────────────────────────────────────────────────────────────
  // Helpers
  // ─────────────────────────────────────────────────────────────

  private def getMetricValue(registry: PrometheusRegistry, name: String, labels: Map[String, String]): Double = {
    val lookupName = name.stripSuffix("_total")
    val snapshots  = registry.scrape()

    snapshots
      .stream()
      .iterator()
      .asScala
      .find(_.getMetadata.getName == lookupName)
      .flatMap { metricSnapshot =>
        metricSnapshot.getDataPoints
          .iterator()
          .asScala
          .find { dataPoint =>
            val dpLabels = dataPoint.getLabels.iterator().asScala.map(l => l.getName -> l.getValue).toMap
            labels.forall { case (k, v) => dpLabels.get(k).contains(v) }
          }
          .map {
            case c: io.prometheus.metrics.model.snapshots.CounterSnapshot.CounterDataPointSnapshot => c.getValue
            case _                                                                                 => 0.0
          }
      }
      .getOrElse(0.0)
  }

  private def getHistogramCount(registry: PrometheusRegistry, name: String, labels: Map[String, String]): Double = {
    val lookupName = name.stripSuffix("_total")
    val snapshots  = registry.scrape()

    snapshots
      .stream()
      .iterator()
      .asScala
      .find(_.getMetadata.getName == lookupName)
      .flatMap { metricSnapshot =>
        metricSnapshot.getDataPoints
          .iterator()
          .asScala
          .find { dataPoint =>
            val dpLabels = dataPoint.getLabels.iterator().asScala.map(l => l.getName -> l.getValue).toMap
            labels.forall { case (k, v) => dpLabels.get(k).contains(v) }
          }
          .collect { case h: io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot =>
            h.getCount.toDouble
          }
      }
      .getOrElse(0.0)
  }
}
