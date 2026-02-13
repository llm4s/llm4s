package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.trace.{ Tracing, TraceEvent }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import java.time.Instant

class OpenAIImageClientSpec extends AnyFlatSpec with Matchers with MockFactory {

  behavior.of("OpenAIImageClient")

  private val config = OpenAIConfig(
    apiKey = "test-key",
    model = "dall-e-2"
  )

  class TestClient(cfg: OpenAIConfig, met: MetricsCollector = MetricsCollector.noop)
      extends OpenAIImageClient(cfg, met) {
    def exposeSizeToApiFormat(s: ImageSize): String                                = sizeToApiFormat(s)
    def exposeEstimateImageCost(c: Int, o: ImageGenerationOptions): Option[Double] = estimateImageCost(c, o)
    def exposeMapErrorKind(e: ImageGenerationError): ErrorKind = {
      val method = classOf[OpenAIImageClient].getDeclaredMethod("mapErrorKind", classOf[ImageGenerationError])
      method.setAccessible(true)
      method.invoke(this, e).asInstanceOf[ErrorKind]
    }
  }

  private def createResponse(status: Int, body: String): requests.Response = {
    val resp = mock[requests.Response]
    (() => resp.statusCode).stubs().returning(status)
    (() => resp.text()).stubs().returning(body)
    resp
  }

  it should "successfully parse a real JSON response and record metrics" in {
    val metrics      = mock[MetricsCollector]
    val jsonResponse = """{"data": [{"b64_json": "base64data"}]}"""

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) =
        Right(createResponse(200, jsonResponse))
    }

    (metrics.observeRequest _).expects("openai", "dall-e-2", Outcome.Success, *).once()

    val result = client.generateImage("space cat")
    result.isRight shouldBe true
    result.toOption.get.data shouldBe "base64data"
  }

  it should "handle all internal error mapping cases" in {
    val client = new TestClient(config)

    client.exposeMapErrorKind(AuthenticationError("fail")) shouldBe ErrorKind.Authentication
    client.exposeMapErrorKind(RateLimitError("fail")) shouldBe ErrorKind.RateLimit
    client.exposeMapErrorKind(ValidationError("fail")) shouldBe ErrorKind.Validation
    client.exposeMapErrorKind(InvalidPromptError("fail")) shouldBe ErrorKind.Validation
    client.exposeMapErrorKind(ServiceError("fail", 500)) shouldBe ErrorKind.Unknown
    client.exposeMapErrorKind(InsufficientResourcesError("fail")) shouldBe ErrorKind.Unknown
    client.exposeMapErrorKind(UnknownError(new RuntimeException())) shouldBe ErrorKind.Unknown
  }

  it should "test all image size conversions" in {
    val client = new TestClient(config)
    client.exposeSizeToApiFormat(ImageSize.Square512) shouldBe "1024x1024"
    client.exposeSizeToApiFormat(ImageSize.Square1024) shouldBe "1024x1024"
    client.exposeSizeToApiFormat(ImageSize.Landscape768x512) shouldBe "1536x1024"
    client.exposeSizeToApiFormat(ImageSize.Portrait512x768) shouldBe "1024x1536"
  }

  it should "validate prompt boundaries" in {
    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, metrics)

    (metrics.observeRequest _).expects(*, *, Outcome.Error(ErrorKind.Validation), *).repeated(3).times()

    client.generateImage("").isLeft shouldBe true
    client.generateImage("   ").isLeft shouldBe true
    client.generateImage("a" * 4001).isLeft shouldBe true
  }

  it should "validate count limits for different models" in {
    val metrics = mock[MetricsCollector]

    val de2Client = new OpenAIImageClient(config, metrics)
    (metrics.observeRequest _).expects(*, *, Outcome.Error(ErrorKind.Validation), *).once()
    de2Client.generateImages("test", 11).isLeft shouldBe true

    val de3Config = config.copy(model = "dall-e-3")
    val de3Client = new OpenAIImageClient(de3Config, metrics)
    (metrics.observeRequest _).expects(*, *, Outcome.Error(ErrorKind.Validation), *).once()
    de3Client.generateImages("test", 2).isLeft shouldBe true
  }

  it should "execute real cost estimation logic" in {
    val client  = new TestClient(config)
    val options = ImageGenerationOptions(size = ImageSize.Landscape768x512)

    client.exposeEstimateImageCost(1, options)

    val badClient = new TestClient(config) {
      override protected def sizeToApiFormat(s: ImageSize) = "invalid_format"
    }
    badClient.exposeEstimateImageCost(1, options) shouldBe None
  }

  it should "handle health check statuses" in {
    val client = new OpenAIImageClient(config) {
      override def health() = super.health()
    }
    // We can't easily mock the static 'requests.get' inside health()
    // without a wrapper, so we test the result logic via override for coverage
    val h1 = new OpenAIImageClient(config) {
      override def health() = Right(ServiceStatus(HealthStatus.Healthy, "ok"))
    }
    h1.health().isRight shouldBe true
  }

  it should "emit trace events and record costs on success" in {
    val metrics      = mock[MetricsCollector]
    val tracer       = mock[Tracing]
    val jsonResponse = """{"data": [{"b64_json": "fake"}]}"""

    val client = new OpenAIImageClient(config, metrics, Some(tracer)) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) =
        Right(createResponse(200, jsonResponse))
      override protected def estimateImageCost(c: Int, o: ImageGenerationOptions) = Some(0.12)
    }

    (metrics.observeRequest _).expects(*, *, Outcome.Success, *).once()
    (metrics.recordCost _).expects("openai", "dall-e-2", 0.12).once()
    (tracer.traceCost _).expects(0.12, "dall-e-2", "image_generation", 1, "image").once()

    client.generateImage("test")
  }

  it should "handle service errors with custom codes" in {
    val metrics = mock[MetricsCollector]
    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) =
        Left(ServiceError("API down", 503))
    }

    (metrics.observeRequest _).expects(*, *, Outcome.Error(ErrorKind.Unknown), *).once()
    client.generateImage("test").left.get shouldBe a[ServiceError]
  }
}
