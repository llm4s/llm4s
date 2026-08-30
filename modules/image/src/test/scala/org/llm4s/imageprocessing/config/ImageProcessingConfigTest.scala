package org.llm4s.imageprocessing.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImageProcessingConfigTest extends AnyFlatSpec with Matchers {

  "OpenAIVisionConfig" should "have default timeout values" in {
    val config = OpenAIVisionConfig(apiKey = "test-key")

    config.connectTimeoutSeconds shouldBe 30
    config.requestTimeoutSeconds shouldBe 60
  }

  it should "accept custom timeout values" in {
    val config = OpenAIVisionConfig(
      apiKey = "test-key",
      connectTimeoutSeconds = 10,
      requestTimeoutSeconds = 120
    )

    config.connectTimeoutSeconds shouldBe 10
    config.requestTimeoutSeconds shouldBe 120
  }

  "AnthropicVisionConfig" should "have default timeout values" in {
    val config = AnthropicVisionConfig(apiKey = "test-key")

    config.connectTimeoutSeconds shouldBe 30
    config.requestTimeoutSeconds shouldBe 60
  }

  it should "accept custom timeout values" in {
    val config = AnthropicVisionConfig(
      apiKey = "test-key",
      connectTimeoutSeconds = 15,
      requestTimeoutSeconds = 90
    )

    config.connectTimeoutSeconds shouldBe 15
    config.requestTimeoutSeconds shouldBe 90
  }
}
