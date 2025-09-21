package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.{ HuggingFaceConfig, ImageGenerationOptions }
import org.scalamock.scalatest.MockFactory
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class HuggingFaceClientTest extends AnyFlatSpec with Matchers with MockFactory with EitherValues {

  val httpClient: BaseHttpClient = stub[BaseHttpClient]

  "buildPayload" should "create a valid JSON payload with all parameters" in {
    // Arrange
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt = "A beautiful sunset over mountains"
    val options = ImageGenerationOptions(
      guidanceScale = 7.5,
      inferenceSteps = 50,
      negativePrompt = Some("blurry, low quality"),
      seed = Some(42L)
    )

    // Act
    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    // Assert
    // Parse the JSON to verify its structure
    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.inputs shouldBe prompt
    parsedPayload.parameters.guidance_scale shouldBe 7.5
    parsedPayload.parameters.inferenceSteps shouldBe 50
    parsedPayload.parameters.negative_prompt shouldBe Some("blurry, low quality")
    parsedPayload.parameters.seed shouldBe Some(42L)
  }

  it should "create a payload with minimal options" in {
    // Arrange
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt  = "A minimalist landscape"
    val options = ImageGenerationOptions() // Default options

    // Act
    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    // Assert
    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.inputs shouldBe prompt
    parsedPayload.parameters.guidance_scale shouldBe 7.5 // Default value
    parsedPayload.parameters.inferenceSteps shouldBe 20  // Default value
    parsedPayload.parameters.negative_prompt shouldBe None
    parsedPayload.parameters.seed shouldBe None
  }

  it should "handle special characters in the prompt" in {
    // Arrange
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt  = "A scene with \"quotes\" and special ch@r@cters!"
    val options = ImageGenerationOptions()

    // Act
    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    // Assert
    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.inputs shouldBe prompt
  }

  it should "create a payload with custom guidance scale and inference steps" in {
    // Arrange
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt = "A futuristic cityscape"
    val options = ImageGenerationOptions(
      guidanceScale = 9.0,
      inferenceSteps = 75
    )

    // Act
    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    // Assert
    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.parameters.guidance_scale shouldBe 9.0
    parsedPayload.parameters.inferenceSteps shouldBe 75
  }

  it should "create a payload with a specific seed" in {
    // Arrange
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt = "A reproducible image generation"
    val options = ImageGenerationOptions(
      seed = Some(12345L)
    )

    // Act
    val payload = client.createJsonPayload(HuggingClientPayload(prompt, options))

    // Assert
    val parsedPayload = read[HuggingClientPayload](payload)
    parsedPayload.parameters.seed shouldBe Some(12345L)
  }

  "buildPayload" should "successfully create a valid payload string" in {
    // Arrange
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt  = "test prompt"
    val options = ImageGenerationOptions()

    // Act
    val result = client.buildPayload(prompt, options)

    // Assert
    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe prompt
      parsedPayload.parameters.guidance_scale shouldBe options.guidanceScale
      parsedPayload.parameters.inferenceSteps shouldBe options.inferenceSteps
    }
  }

  it should "create a payload with all custom options" in {
    // Arrange
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt = "test prompt"
    val options = ImageGenerationOptions(
      guidanceScale = 8.5,
      inferenceSteps = 30,
      negativePrompt = Some("negative test"),
      seed = Some(123L)
    )

    // Act
    val result = client.buildPayload(prompt, options)

    // Assert
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
    // Arrange
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt  = ""
    val options = ImageGenerationOptions()

    // Act
    val result = client.buildPayload(prompt, options)

    // Assert
    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe empty
    }
  }

  it should "handle special characters in the prompt correctly" in {
    // Arrange
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"), httpClient)
    val prompt  = "test \"quote\" and \n newline"
    val options = ImageGenerationOptions()

    // Act
    val result = client.buildPayload(prompt, options)

    // Assert
    result.value.length should be > 0

    result.map { jsonStr =>
      val parsedPayload = read[HuggingClientPayload](jsonStr)
      parsedPayload.inputs shouldBe prompt
    }
  }

}
