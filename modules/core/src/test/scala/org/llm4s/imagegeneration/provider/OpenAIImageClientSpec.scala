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

  private def successfulImage(prompt: String) =
    GeneratedImage(
      data = "fake",
      format = ImageFormat.PNG,
      size = ImageSize.Square1024,
      createdAt = java.time.Instant.now(),
      prompt = prompt,
      seed = None,
      filePath = None
    )

  // -----------------------------
  // SUCCESS PATH
  // -----------------------------

  it should "record success metrics when generation succeeds" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) = Right(null)
      override protected def parseResponse(r: requests.Response, p: String, o: ImageGenerationOptions) =
        Right(Seq(successfulImage(p)))
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    client.generateImage("test").isRight shouldBe true
  }

  it should "return error when API returns empty image list" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) = Right(null)
      override protected def parseResponse(r: requests.Response, p: String, o: ImageGenerationOptions) =
        Right(Seq.empty)
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    client.generateImage("test").isLeft shouldBe true
  }

  // -----------------------------
  // ERROR METRICS
  // -----------------------------

  it should "record authentication error metrics" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) =
        Left(AuthenticationError("Invalid API key"))
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Error(ErrorKind.Authentication), *)
      .once()

    client.generateImage("test").isLeft shouldBe true
  }

  it should "record rate limit metrics" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) =
        Left(RateLimitError("Rate limit exceeded"))
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Error(ErrorKind.RateLimit), *)
      .once()

    client.generateImage("test").isLeft shouldBe true
  }

  // -----------------------------
  // COST LOGIC
  // -----------------------------

  it should "record cost when pricing exists" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) = Right(null)
      override protected def parseResponse(r: requests.Response, p: String, o: ImageGenerationOptions) =
        Right(Seq(successfulImage(p)))

      override protected def estimateImageCost(c: Int, o: ImageGenerationOptions) =
        Some(0.02)
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    (metrics.recordCost _)
      .expects("openai", "dall-e-2", 0.02)
      .once()

    client.generateImage("test")
  }

  it should "not record cost when pricing is absent" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) = Right(null)
      override protected def parseResponse(r: requests.Response, p: String, o: ImageGenerationOptions) =
        Right(Seq(successfulImage(p)))

      override protected def estimateImageCost(c: Int, o: ImageGenerationOptions) =
        None
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    (metrics.recordCost _).expects(*, *, *).never()

    client.generateImage("test")
  }

  it should "emit tracer event when pricing exists" in {

    val metrics = mock[MetricsCollector]
    val tracer  = mock[Tracing]

    val client = new OpenAIImageClient(config, metrics, Some(tracer)) {
      override protected def makeApiRequest(p: String, c: Int, o: ImageGenerationOptions) = Right(null)
      override protected def parseResponse(r: requests.Response, p: String, o: ImageGenerationOptions) =
        Right(Seq(successfulImage(p)))

      override protected def estimateImageCost(c: Int, o: ImageGenerationOptions) =
        Some(0.05)
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    (metrics.recordCost _)
      .expects("openai", "dall-e-2", 0.05)
      .once()

    (tracer
      .traceEvent(_: TraceEvent))
      .expects(*)
      .once()
      .returning(Right(()))

    client.generateImage("test")
  }

  // -----------------------------
  // VALIDATION BRANCHES
  // -----------------------------

  it should "validate empty prompt" in {

    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, metrics)

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Error(ErrorKind.Validation), *)
      .once()

    client.generateImage("   ").isLeft shouldBe true
  }

  it should "validate too long prompt" in {

    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, metrics)

    val longPrompt = "a" * 5001

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Error(ErrorKind.Validation), *)
      .once()

    client.generateImage(longPrompt).isLeft shouldBe true
  }

  it should "validate negative count" in {

    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, metrics)

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Error(ErrorKind.Validation), *)
      .once()

    client.generateImages("test", -1).isLeft shouldBe true
  }

}
