package org.llm4s.speech.config

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class SpeechConfigSpec extends AnyFlatSpec with Matchers {

  "TTSConfig" should "store all fields correctly" in {
    val cfg = TTSConfig(
      provider = "openai",
      model = "tts-1",
      voice = "alloy",
      apiKey = "sk-test",
      baseUrl = "https://api.openai.com",
      region = None
    )

    cfg.provider shouldBe "openai"
    cfg.model shouldBe "tts-1"
    cfg.voice shouldBe "alloy"
    cfg.apiKey shouldBe "sk-test"
    cfg.baseUrl shouldBe "https://api.openai.com"
    cfg.region shouldBe None
  }

  it should "store region when provided" in {
    val cfg = TTSConfig(
      provider = "azure",
      model = "neural",
      voice = "en-US-JennyNeural",
      apiKey = "azure-key",
      baseUrl = "default",
      region = Some("eastus")
    )

    cfg.region shouldBe Some("eastus")
  }

  it should "redact API key in toString" in {
    val cfg = TTSConfig(
      provider = "openai",
      model = "tts-1",
      voice = "alloy",
      apiKey = "sk-secret-key-12345",
      baseUrl = "https://api.openai.com"
    )

    val str = cfg.toString
    (str should not).include("sk-secret-key-12345")
    str should include("openai")
    str should include("tts-1")
  }

  it should "have correct default constants" in {
    TTSConfig.DEFAULT_OPENAI_BASE_URL shouldBe "https://api.openai.com"
    TTSConfig.DEFAULT_ELEVENLABS_BASE_URL shouldBe "https://api.elevenlabs.io"
    TTSConfig.DEFAULT_OPENAI_MODEL shouldBe "tts-1"
    TTSConfig.DEFAULT_OPENAI_VOICE shouldBe "alloy"
  }

  "STTConfig" should "store all fields correctly" in {
    val cfg = STTConfig(
      provider = "openai",
      model = "whisper-1",
      apiKey = "sk-test",
      baseUrl = "https://api.openai.com",
      region = None
    )

    cfg.provider shouldBe "openai"
    cfg.model shouldBe "whisper-1"
    cfg.apiKey shouldBe "sk-test"
    cfg.baseUrl shouldBe "https://api.openai.com"
    cfg.region shouldBe None
  }

  it should "store region when provided for Azure" in {
    val cfg = STTConfig(
      provider = "azure",
      model = "conversation",
      apiKey = "azure-key",
      baseUrl = "default",
      region = Some("westeurope")
    )

    cfg.region shouldBe Some("westeurope")
  }

  it should "redact API key in toString" in {
    val cfg = STTConfig(
      provider = "openai",
      model = "whisper-1",
      apiKey = "sk-very-secret-key",
      baseUrl = "https://api.openai.com"
    )

    val str = cfg.toString
    (str should not).include("sk-very-secret-key")
    str should include("openai")
    str should include("whisper-1")
  }

  it should "have correct default constants" in {
    STTConfig.DEFAULT_OPENAI_BASE_URL shouldBe "https://api.openai.com"
    STTConfig.DEFAULT_OPENAI_MODEL shouldBe "whisper-1"
  }
}
