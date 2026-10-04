package org.llm4s.imageprocessing

import org.llm4s.imageprocessing.config.GeminiVisionConfig
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ImageProcessingGeminiSpec extends AnyFlatSpec with Matchers {

  "GeminiVisionConfig" should "use default values" in {
    val cfg = GeminiVisionConfig(apiKey = "key")
    cfg.model shouldBe "gemini-3.6-flash"
    // Google shut these down (ai.google.dev/gemini-api/docs/changelog: 1.5 on 2025-09-29, 2.0 on 2026-06-01).
    val shutDown = Set(
      "gemini-1.5-flash",
      "gemini-1.5-flash-8b",
      "gemini-1.5-pro",
      "gemini-2.0-flash",
      "gemini-2.0-flash-001",
      "gemini-2.0-flash-lite",
      "gemini-2.0-flash-lite-001"
    )
    shutDown should not contain cfg.model
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

  it should "default the client factory to a model that is still served" in {
    val m = classOf[ImageProcessing.type].getMethods.find(_.getName.startsWith("geminiVisionClient$default$2"))
    m.map(_.invoke(ImageProcessing)) shouldBe Some("gemini-3.6-flash")
  }

  it should "accept a custom model name" in {
    val client = ImageProcessing.geminiVisionClient("test-key", "gemini-1.0-pro-vision")
    client should not be null
  }
}
