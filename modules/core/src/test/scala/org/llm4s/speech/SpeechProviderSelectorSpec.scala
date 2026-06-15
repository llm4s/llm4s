package org.llm4s.speech

import org.llm4s.error.ConfigurationError
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

@scala.annotation.nowarn("msg=Could not verify")
class SpeechProviderSelectorSpec extends AnyFlatSpec with Matchers {

  // ========== TTS Client Selection ===========

  private val openAiTTSCfg = TTSConfig(
    provider = "openai",
    model = "tts-1",
    voice = "alloy",
    apiKey = "sk-key",
    baseUrl = "https://api.openai.com"
  )

  private val elevenLabsCfg = TTSConfig(
    provider = "elevenlabs",
    model = "eleven_multilingual_v2",
    voice = "voiceId123",
    apiKey = "el-key",
    baseUrl = "https://api.elevenlabs.io"
  )

  private val azureTTSCfg = TTSConfig(
    provider = "azure",
    model = "neural",
    voice = "en-US-JennyNeural",
    apiKey = "azure-key",
    baseUrl = "default",
    region = Some("westus")
  )

  "SpeechProviderSelector.getTTSClient" should "return OpenAITTSClient for 'openai' provider" in {
    val result = SpeechProviderSelector.getTTSClient(openAiTTSCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "openai-tts"
  }

  it should "return ElevenLabsTTSClient for 'elevenlabs' provider" in {
    val result = SpeechProviderSelector.getTTSClient(elevenLabsCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "elevenlabs-tts"
  }

  it should "return AzureTTSClient for 'azure' provider" in {
    val result = SpeechProviderSelector.getTTSClient(azureTTSCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "azure-tts"
  }

  it should "be case-insensitive for provider name" in {
    val upperCfg = openAiTTSCfg.copy(provider = "OpenAI")
    val result   = SpeechProviderSelector.getTTSClient(upperCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "openai-tts"
  }

  it should "return ConfigurationError for unknown TTS provider" in {
    val unknownCfg = openAiTTSCfg.copy(provider = "unknown-provider")
    val result     = SpeechProviderSelector.getTTSClient(unknownCfg)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("unknown-provider")
    result.left.toOption.get.message should include("openai")
    result.left.toOption.get.message should include("elevenlabs")
    result.left.toOption.get.message should include("azure")
  }

  // ========== STT Client Selection ===========

  private val openAiSTTCfg = STTConfig(
    provider = "openai",
    model = "whisper-1",
    apiKey = "sk-key",
    baseUrl = "https://api.openai.com"
  )

  private val azureSTTCfg = STTConfig(
    provider = "azure",
    model = "conversation",
    apiKey = "azure-key",
    baseUrl = "default",
    region = Some("eastus")
  )

  "SpeechProviderSelector.getSTTClient" should "return OpenAISTTClient for 'openai' provider" in {
    val result = SpeechProviderSelector.getSTTClient(openAiSTTCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "openai-stt"
  }

  it should "return AzureSTTClient for 'azure' provider" in {
    val result = SpeechProviderSelector.getSTTClient(azureSTTCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "azure-stt"
  }

  it should "be case-insensitive for provider name" in {
    val upperCfg = openAiSTTCfg.copy(provider = "OpenAI")
    val result   = SpeechProviderSelector.getSTTClient(upperCfg)
    result.isRight shouldBe true
    result.toOption.get.name shouldBe "openai-stt"
  }

  it should "return ConfigurationError for unknown STT provider" in {
    val unknownCfg = openAiSTTCfg.copy(provider = "unknown-stt-provider")
    val result     = SpeechProviderSelector.getSTTClient(unknownCfg)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
    result.left.toOption.get.message should include("unknown-stt-provider")
    result.left.toOption.get.message should include("openai")
    result.left.toOption.get.message should include("azure")
  }

  // ========== parseModelSpec ===========

  "SpeechProviderSelector.parseModelSpec" should "parse valid 'openai/tts-1' spec" in {
    val result = SpeechProviderSelector.parseModelSpec("openai/tts-1")
    result shouldBe Right(("openai", "tts-1"))
  }

  it should "parse valid 'elevenlabs/my-voice-id' spec" in {
    val result = SpeechProviderSelector.parseModelSpec("elevenlabs/my-voice-id")
    result shouldBe Right(("elevenlabs", "my-voice-id"))
  }

  it should "parse valid 'azure/en-US' spec" in {
    val result = SpeechProviderSelector.parseModelSpec("azure/en-US")
    result shouldBe Right(("azure", "en-US"))
  }

  it should "normalize provider to lowercase" in {
    val result = SpeechProviderSelector.parseModelSpec("OpenAI/tts-1")
    result shouldBe Right(("openai", "tts-1"))
  }

  it should "handle model paths with slashes correctly (only split on first slash)" in {
    val result = SpeechProviderSelector.parseModelSpec("openai/tts-1/hd")
    result shouldBe Right(("openai", "tts-1/hd"))
  }

  it should "return ConfigurationError for spec without a slash" in {
    val result = SpeechProviderSelector.parseModelSpec("openai-tts-1")
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "return ConfigurationError for empty spec" in {
    val result = SpeechProviderSelector.parseModelSpec("")
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "return ConfigurationError for spec with empty provider" in {
    val result = SpeechProviderSelector.parseModelSpec("/model-only")
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }

  it should "return ConfigurationError for spec with empty model" in {
    val result = SpeechProviderSelector.parseModelSpec("openai/")
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ConfigurationError]
  }
}
