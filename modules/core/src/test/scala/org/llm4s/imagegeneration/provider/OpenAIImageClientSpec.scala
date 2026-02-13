package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.llm4s.trace.Tracing
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

  it should "record success metrics when image generation succeeds" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {

      override protected def makeApiRequest(
        prompt: String,
        count: Int,
        options: ImageGenerationOptions
      ) = Right(null)

      override protected def parseResponse(
        response: requests.Response,
        prompt: String,
        options: ImageGenerationOptions
      ) = Right(Seq(successfulImage(prompt)))
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    client.generateImage("test").isRight shouldBe true
  }

  it should "record authentication error metrics on 401" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override protected def makeApiRequest(
        prompt: String,
        count: Int,
        options: ImageGenerationOptions
      ) =
        Left(AuthenticationError("Invalid API key"))
    }

    (metrics.observeRequest _)
      .expects(
        "openai",
        "dall-e-2",
        Outcome.Error(ErrorKind.Authentication),
        *
      )
      .once()

    client.generateImage("test").isLeft shouldBe true
  }

  it should "record cost when pricing exists" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {

      override protected def makeApiRequest(
        prompt: String,
        count: Int,
        options: ImageGenerationOptions
      ) = Right(null)

      override protected def parseResponse(
        response: requests.Response,
        prompt: String,
        options: ImageGenerationOptions
      ) = Right(Seq(successfulImage(prompt)))

      override protected def estimateImageCost(
        count: Int,
        options: ImageGenerationOptions
      ): Option[Double] =
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

  it should "emit tracer event when pricing exists" in {

    val metrics = mock[MetricsCollector]
    val tracer  = mock[Tracing]

    val client =
      new OpenAIImageClient(config, metrics, Some(tracer)) {

        override protected def makeApiRequest(
          prompt: String,
          count: Int,
          options: ImageGenerationOptions
        ) = Right(null)

        override protected def parseResponse(
          response: requests.Response,
          prompt: String,
          options: ImageGenerationOptions
        ) = Right(Seq(successfulImage(prompt)))

        override protected def estimateImageCost(
          count: Int,
          options: ImageGenerationOptions
        ): Option[Double] =
          Some(0.05)
      }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    (metrics.recordCost _)
      .expects("openai", "dall-e-2", 0.05)
      .once()

    // 👇 IMPORTANT: mock traceEvent, not traceCost
    (tracer
      .traceEvent(_: org.llm4s.trace.TraceEvent))
      .expects(*)
      .once()
      .returning(Right(()))

    client.generateImage("test")
  }

  it should "return validation error for empty prompt" in {

    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, metrics)

    (metrics.observeRequest _)
      .expects(
        "openai",
        "dall-e-2",
        Outcome.Error(ErrorKind.Validation),
        *
      )
      .once()

    client.generateImage("   ").isLeft shouldBe true
  }

  it should "return validation error when count exceeds limit" in {

    val metrics = mock[MetricsCollector]
    val client  = new OpenAIImageClient(config, metrics)

    (metrics.observeRequest _)
      .expects(
        "openai",
        "dall-e-2",
        Outcome.Error(ErrorKind.Validation),
        *
      )
      .once()

    client.generateImages("test", 100).isLeft shouldBe true
  }

  it should "return Healthy when health endpoint returns 200" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override def health() =
        Right(ServiceStatus(HealthStatus.Healthy, "OpenAI API is responding"))
    }

    client.health() match {
      case Right(status) =>
        status.status shouldBe HealthStatus.Healthy
      case Left(err) =>
        fail(s"Expected Right but got $err")
    }
  }

  it should "return Degraded when health endpoint returns 429" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override def health() =
        Right(ServiceStatus(HealthStatus.Degraded, "Rate limited but operational"))
    }

    client.health() match {
      case Right(status) =>
        status.status shouldBe HealthStatus.Degraded
      case Left(err) =>
        fail(s"Expected Right but got $err")
    }
  }

  it should "return Unhealthy for other status codes" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {
      override def health() =
        Right(ServiceStatus(HealthStatus.Unhealthy, "API returned status 500"))
    }

    client.health() match {
      case Right(status) =>
        status.status shouldBe HealthStatus.Unhealthy
      case Left(err) =>
        fail(s"Expected Right but got $err")
    }
  }
}
