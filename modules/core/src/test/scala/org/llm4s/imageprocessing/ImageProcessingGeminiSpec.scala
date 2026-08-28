package org.llm4s.imageprocessing

import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImageProcessingGeminiSpec extends AnyFlatSpec with Matchers {

  "GeminiVisionConfig" should "use default values" in {
    val cfg = GeminiVisionConfig(apiKey = "key")
    cfg.model shouldBe "gemini-1.5-flash"
    cfg.baseUrl should include("googleapis")
    cfg.connectTimeoutSeconds shouldBe 30
    cfg.requestTimeoutSeconds shouldBe 60
  }

  it should "accept custom values" in {
    val cfg = GeminiVisionConfig("key", "gemini-1.0", "https://custom.url", 5, 10)
    cfg.model shouldBe "gemini-1.0"
    cfg.baseUrl shouldBe "https://custom.url"
    cfg.connectTimeoutSeconds shouldBe 5
    cfg.requestTimeoutSeconds shouldBe 10
  }

  it should "be an ImageProcessingConfig" in {
    val cfg = GeminiVisionConfig(apiKey = "key")
    cfg shouldBe a[org.llm4s.imageprocessing.config.ImageProcessingConfig]
  }

  "ImageProcessing.geminiVisionClient" should "create a GeminiVisionClient instance" in {
    val client = ImageProcessing.geminiVisionClient("test-key")
    client should not be null
    client shouldBe a[ImageProcessingClient]
  }

  it should "accept a custom model name" in {
    val client = ImageProcessing.geminiVisionClient("test-key", "gemini-1.0-pro-vision")
    client should not be null
  }
}
