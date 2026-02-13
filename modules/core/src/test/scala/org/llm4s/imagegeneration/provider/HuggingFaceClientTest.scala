package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.{ HuggingFaceConfig, ImageGenerationOptions, ServiceError }
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._
import scala.util.{ Failure, Success }

class HuggingFaceClientTest extends AnyFlatSpec with Matchers with MockFactory with EitherValues {

  val httpClient: HttpClient = stub[HttpClient]
  val config                 = HuggingFaceConfig("test-key", "test-model")
  val client                 = new HuggingFaceClient(config, httpClient)

  "buildPayload" should "create a valid JSON payload with all parameters" in {
    val prompt = "A beautiful sunset over mountains"
    val options = ImageGenerationOptions(
      guidanceScale = 7.5,
      inferenceSteps = 50,
      negativePrompt = Some("blurry, low quality"),
      seed = Some(42L)
    )

    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.inputs shouldBe prompt
    parsedPayload.parameters.guidance_scale shouldBe 7.5
    parsedPayload.parameters.inferenceSteps shouldBe 50
    parsedPayload.parameters.negative_prompt shouldBe Some("blurry, low quality")
    parsedPayload.parameters.seed shouldBe Some(42L)
  }

  it should "create a payload with minimal options" in {
    val prompt  = "A minimalist landscape"
    val options = ImageGenerationOptions()

    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.inputs shouldBe prompt
    parsedPayload.parameters.guidance_scale shouldBe 7.5
    parsedPayload.parameters.inferenceSteps shouldBe 20
    parsedPayload.parameters.negative_prompt shouldBe None
    parsedPayload.parameters.seed shouldBe None
  }

  it should "handle special characters in the prompt" in {
    val prompt  = "A scene with \"quotes\" and special ch@r@cters!"
    val options = ImageGenerationOptions()

    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.inputs shouldBe prompt
  }

  it should "create a payload with custom guidance scale and inference steps" in {
    val prompt = "A futuristic cityscape"
    val options = ImageGenerationOptions(
      guidanceScale = 9.0,
      inferenceSteps = 75
    )

    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.parameters.guidance_scale shouldBe 9.0
    parsedPayload.parameters.inferenceSteps shouldBe 75
  }

  it should "create a payload with a specific seed" in {
    val prompt = "A reproducible image generation"
    val options = ImageGenerationOptions(
      seed = Some(12345L)
    )

    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.parameters.seed shouldBe Some(12345L)
  }

  "buildPayload" should "successfully create a valid payload string" in {
    val prompt  = "test prompt"
    val options = ImageGenerationOptions()

    val result = client.buildPayload(prompt, options)

    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe prompt
      parsedPayload.parameters.guidance_scale shouldBe options.guidanceScale
      parsedPayload.parameters.inferenceSteps shouldBe options.inferenceSteps
    }
  }

  it should "create a payload with all custom options" in {
    val prompt = "test prompt"
    val options = ImageGenerationOptions(
      guidanceScale = 8.5,
      inferenceSteps = 30,
      negativePrompt = Some("negative test"),
      seed = Some(123L)
    )

    val result = client.buildPayload(prompt, options)

    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe prompt
      parsedPayload.parameters.guidance_scale shouldBe 8.5
      parsedPayload.parameters.inferenceSteps shouldBe 30
      parsedPayload.parameters.negative_prompt shouldBe Some("negative test")
      parsedPayload.parameters.seed shouldBe Some(123L)
    }
  }

  it should "handle empty prompt correctly" in {
    val prompt  = ""
    val options = ImageGenerationOptions()

    val result = client.buildPayload(prompt, options)

    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe empty
    }
  }

  it should "handle special characters in the prompt correctly" in {
    val prompt  = "test \"quote\" and \n newline"
    val options = ImageGenerationOptions()

    val result = client.buildPayload(prompt, options)

    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe prompt
    }
  }

  "makeHttpRequest" should "return a Left(error) on exception" in {
    val exception = new RuntimeException("Something went wrong")
    (httpClient.post _).when(*, *, *, *).returns(Failure(exception))

    val result = client.makeHttpRequest("something")

    result.isLeft should be(true)
    result.left.value shouldBe a[ServiceError]
    result.left.value.message shouldBe "Something went wrong"
  }

  it should "return a Right(value) on success" in {
    val response = requests.Response(
      url = "http://test.com",
      statusCode = 200,
      statusMessage = "OK",
      data = new geny.Bytes("something".getBytes),
      headers = Map.empty,
      history = None
    )
    (httpClient.post _).when(*, *, *, *).returns(Success(response))

    val result = client.makeHttpRequest("something")

    result.isRight should be(true)
    result.value should be(response)
  }
}
