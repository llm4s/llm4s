package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.trace.{ Tracing, TraceEvent }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory

class OpenAIImageClientSpec extends AnyFlatSpec with Matchers with MockFactory {

  behavior.of("OpenAIImageClient")

  private val config = OpenAIConfig(
    apiKey = "test-key",
    model = "dall-e-2"
  )

  // ----------------------------------------------------------
  // Helper subclass
  // ----------------------------------------------------------

  class TestClient(
    cfg: OpenAIConfig,
    met: MetricsCollector = MetricsCollector.noop
  ) extends OpenAIImageClient(cfg, HttpClient.create(), met) {

    def exposeSizeToApiFormat(s: ImageSize): String =
      sizeToApiFormat(s)

    def exposeEstimateImageCost(
      c: Int,
      o: ImageGenerationOptions
    ): Option[Double] =
      estimateImageCost(c, o)

    def exposeMapErrorKind(e: ImageGenerationError): ErrorKind = {
      val method =
        classOf[OpenAIImageClient]
          .getDeclaredMethod("mapErrorKind", classOf[ImageGenerationError])
      method.setAccessible(true)
      method.invoke(this, e).asInstanceOf[ErrorKind]
    }
  }

  private def createResponse(status: Int, body: String): requests.Response =
    requests.Response(
      url = "http://test",
      statusCode = status,
      statusMessage = if (status == 200) "OK" else "Error",
      data = new geny.Bytes(body.getBytes),
      headers = Map.empty,
      history = None
    )

  // ==========================================================
  // Success parsing + metrics
  // ==========================================================

  it should "successfully parse a real JSON response and record metrics" in {
    val metrics      = mock[MetricsCollector]
    val jsonResponse = """{"data":[{"b64_json":"base64data"}]}"""

    val client =
      new OpenAIImageClient(config, HttpClient.create(), metrics) {
        override protected def makeApiRequest(
          p: String,
          c: Int,
          o: ImageGenerationOptions
        ) =
          Right(createResponse(200, jsonResponse))
      }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    val result = client.generateImage("space cat")

    result match {
      case Right(img) =>
        img.data shouldBe "base64data"
      case Left(e) =>
        fail(s"Expected Right but got Left($e)")
    }
  }

  // ==========================================================
  // Error mapping
  // ==========================================================

  it should "handle all internal error mapping cases" in {
    val client = new TestClient(config)

    client.exposeMapErrorKind(
      AuthenticationError("x")
    ) shouldBe ErrorKind.Authentication
    client.exposeMapErrorKind(RateLimitError("x")) shouldBe ErrorKind.RateLimit
    client.exposeMapErrorKind(
      ValidationError("x")
    ) shouldBe ErrorKind.Validation
    client.exposeMapErrorKind(
      InvalidPromptError("x")
    ) shouldBe ErrorKind.Validation
    client.exposeMapErrorKind(ServiceError("x", 500)) shouldBe ErrorKind.Unknown
    client.exposeMapErrorKind(
      InsufficientResourcesError("x")
    ) shouldBe ErrorKind.Unknown
    client.exposeMapErrorKind(
      UnknownError(new RuntimeException())
    ) shouldBe ErrorKind.Unknown
  }

  // ==========================================================
  // Size conversion (model-aware)
  // ==========================================================

  it should "respect model-specific image size mappings" in {

    val de2Client =
      new TestClient(config.copy(model = "dall-e-2"))

    de2Client.exposeSizeToApiFormat(ImageSize.Square512) shouldBe "512x512"
    de2Client.exposeSizeToApiFormat(ImageSize.Square1024) shouldBe "1024x1024"

    val de3Client =
      new TestClient(config.copy(model = "dall-e-3"))

    de3Client.exposeSizeToApiFormat(ImageSize.Square512) shouldBe "1024x1024"
    de3Client.exposeSizeToApiFormat(
      ImageSize.Landscape768x512
    ) shouldBe "1536x1024"
    de3Client.exposeSizeToApiFormat(
      ImageSize.Portrait512x768
    ) shouldBe "1024x1536"

    val gptClient =
      new TestClient(config.copy(model = "gpt-image-1"))

    gptClient.exposeSizeToApiFormat(ImageSize.Square512) shouldBe "1024x1024"
  }

  // ==========================================================
  // Prompt validation
  // ==========================================================

  it should "validate prompt boundaries" in {
    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, HttpClient.create(), metrics)

    (metrics.observeRequest _)
      .expects(*, *, Outcome.Error(ErrorKind.Validation), *)
      .repeat(3)

    client.generateImage("").isLeft shouldBe true
    client.generateImage("   ").isLeft shouldBe true
    client.generateImage("a" * 4001).isLeft shouldBe true
  }

  // ==========================================================
  // Count validation
  // ==========================================================

  it should "validate count limits for different models" in {
    val metrics = mock[MetricsCollector]

    val de2Client =
      new OpenAIImageClient(config, HttpClient.create(), metrics)

    (metrics.observeRequest _)
      .expects(*, *, Outcome.Error(ErrorKind.Validation), *)
      .once()

    de2Client.generateImages("test", 11).isLeft shouldBe true

    val de3Client =
      new OpenAIImageClient(
        config.copy(model = "dall-e-3"),
        HttpClient.create(),
        metrics
      )

    (metrics.observeRequest _)
      .expects(*, *, Outcome.Error(ErrorKind.Validation), *)
      .once()

    de3Client.generateImages("test", 2).isLeft shouldBe true
  }

  // ==========================================================
  // Cost + tracing
  // ==========================================================

  it should "emit trace events and record costs on success" in {
    val metrics      = mock[MetricsCollector]
    val tracer       = mock[Tracing]
    val jsonResponse = """{"data":[{"b64_json":"fake"}]}"""

    val client =
      new OpenAIImageClient(
        config,
        HttpClient.create(),
        metrics,
        Some(tracer)
      ) {

        override protected def makeApiRequest(
          p: String,
          c: Int,
          o: ImageGenerationOptions
        ) =
          Right(createResponse(200, jsonResponse))

        override protected def estimateImageCost(
          c: Int,
          o: ImageGenerationOptions
        ) = Some(0.12)
      }

    (metrics.observeRequest _)
      .expects(*, *, Outcome.Success, *)
      .once()

    (metrics.recordCost _)
      .expects("openai", "dall-e-2", 0.12)
      .once()

    (tracer
      .traceEvent(_: TraceEvent))
      .expects(*)
      .returning(Right(()))
      .once()

    client.generateImage("test")
  }

  // ==========================================================
  // Service error
  // ==========================================================

  it should "handle service errors with custom codes" in {
    val metrics = mock[MetricsCollector]

    val client =
      new OpenAIImageClient(config, HttpClient.create(), metrics) {
        override protected def makeApiRequest(
          p: String,
          c: Int,
          o: ImageGenerationOptions
        ) =
          Left(ServiceError("API down", 503))
      }

    (metrics.observeRequest _)
      .expects(*, *, Outcome.Error(ErrorKind.Unknown), *)
      .once()

    client.generateImage("test").isLeft shouldBe true
  }
}
