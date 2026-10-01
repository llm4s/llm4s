package org.llm4s.imageprocessing.config

import scala.concurrent.duration.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImageProcessingConfigTest extends AnyFlatSpec with Matchers {

  "OpenAIVisionConfig" should "have default timeout values" in {
    val config = OpenAIVisionConfig(apiKey = "test-key")

    config.connectTimeout shouldBe 30.seconds
    config.requestTimeout shouldBe 60.seconds
  }

  it should "accept custom timeout values" in {
    val config = OpenAIVisionConfig(
      apiKey = "test-key",
      connectTimeout = 10.seconds,
      requestTimeout = 120.seconds
    )

    config.connectTimeout shouldBe 10.seconds
    config.requestTimeout shouldBe 120.seconds
  }

  "AnthropicVisionConfig" should "have default timeout values" in {
    val config = AnthropicVisionConfig(apiKey = "test-key")

    config.connectTimeout shouldBe 30.seconds
    config.requestTimeout shouldBe 60.seconds
  }

  it should "accept custom timeout values" in {
    val config = AnthropicVisionConfig(
      apiKey = "test-key",
      connectTimeout = 15.seconds,
      requestTimeout = 90.seconds
    )

    config.connectTimeout shouldBe 15.seconds
    config.requestTimeout shouldBe 90.seconds
  }
}
