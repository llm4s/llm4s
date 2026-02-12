package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration._
import org.llm4s.metrics.{ ErrorKind, MetricsCollector, Outcome }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory

class OpenAIImageClientSpec extends AnyFlatSpec with Matchers with MockFactory {

  behavior.of("OpenAIImageClient")

  private val config = OpenAIConfig(
    apiKey = "test-key",
    model = "dall-e-2"
  )

  it should "record success metrics when image generation succeeds" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {

      override protected def makeApiRequest(
        prompt: String,
        count: Int,
        options: ImageGenerationOptions
      ): Either[ImageGenerationError, requests.Response] =
        Right(null) // We bypass response parsing entirely

      override protected def parseResponse(
        response: requests.Response,
        prompt: String,
        options: ImageGenerationOptions
      ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

        // explicitly reference params to avoid unused warnings
        val _ = response
        val _ = prompt
        val _ = options

        Right(
          Seq(
            GeneratedImage(
              data = "fakeBase64",
              format = ImageFormat.PNG,
              size = ImageSize.Square1024,
              createdAt = java.time.Instant.now(),
              prompt = "test prompt",
              seed = None,
              filePath = None
            )
          )
        )
      }
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    val result = client.generateImage("test prompt")

    result.isRight shouldBe true
  }

  it should "record authentication error metrics on 401" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {

      override protected def makeApiRequest(
        prompt: String,
        count: Int,
        options: ImageGenerationOptions
      ): Either[ImageGenerationError, requests.Response] =
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

    val result = client.generateImage("test prompt")

    result.isLeft shouldBe true
  }

  it should "record cost when pricing metadata exists" in {

    val metrics = mock[MetricsCollector]

    val client = new OpenAIImageClient(config, metrics) {

      override protected def makeApiRequest(
        prompt: String,
        count: Int,
        options: ImageGenerationOptions
      ): Either[ImageGenerationError, requests.Response] =
        Right(null)

      override protected def parseResponse(
        response: requests.Response,
        prompt: String,
        options: ImageGenerationOptions
      ): Either[ImageGenerationError, Seq[GeneratedImage]] = {

        val _ = response
        val _ = prompt
        val _ = options

        Right(
          Seq(
            GeneratedImage(
              data = "fakeBase64",
              format = ImageFormat.PNG,
              size = ImageSize.Square1024,
              createdAt = java.time.Instant.now(),
              prompt = "test prompt",
              seed = None,
              filePath = None
            )
          )
        )
      }
    }

    (metrics.observeRequest _)
      .expects("openai", "dall-e-2", Outcome.Success, *)
      .once()

    (metrics.recordCost _)
      .expects("openai", "dall-e-2", *)
      .anyNumberOfTimes()

    client.generateImage("test prompt")
  }
}
