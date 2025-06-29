package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.{ HuggingFaceConfig, ImageGenerationOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default._

class HuggingFaceClientTest extends AnyFlatSpec with Matchers {

  "buildPayload" should "create a valid JSON payload with all parameters" in {
    // Arrange
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"))
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
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"))
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
    val client  = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"))
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
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"))
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
    val client = new HuggingFaceClient(HuggingFaceConfig("test-key", "test-model"))
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
}
