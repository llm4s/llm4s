package org.llm4s.speech.config

import org.llm4s.config.ReferenceConfig
import org.llm4s.error.ConfigurationError
import org.llm4s.speech.SpeechProviderSelector
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/**
 * `SpeechConfigLoader` against this module's `reference.conf`, with the environment supplied by
 * the test (the real process environment is switched off).
 */
class SpeechConfigLoaderSpec extends AnyWordSpec with Matchers with EitherValues {

  private def env(pairs: (String, String)*) = ReferenceConfig.withEnv("", pairs.toMap)

  "SpeechConfigLoader.tts" should {

    "select OpenAI from OPENAI_API_KEY, defaulting the voice and base URL" in {
      SpeechConfigLoader
        .tts(env("SPEECH_TTS_MODEL" -> "openai/tts-1-hd", "OPENAI_API_KEY" -> "sk-1"))
        .value shouldBe TTSConfig("openai", "tts-1-hd", "alloy", "sk-1", "https://api.openai.com")
    }

    "honour SPEECH_TTS_VOICE and OPENAI_SPEECH_BASE_URL" in {
      SpeechConfigLoader
        .tts(
          env(
            "SPEECH_TTS_MODEL"       -> "openai/tts-1",
            "SPEECH_TTS_VOICE"       -> "nova",
            "OPENAI_API_KEY"         -> "sk-1",
            "OPENAI_SPEECH_BASE_URL" -> "https://proxy.example/"
          )
        )
        .value shouldBe TTSConfig("openai", "tts-1", "nova", "sk-1", "https://proxy.example")
    }

    "take the ElevenLabs voice id from the model spec and the model id from config" in {
      SpeechConfigLoader
        .tts(env("SPEECH_TTS_MODEL" -> "elevenlabs/voice123", "ELEVENLABS_API_KEY" -> "el-1"))
        .value shouldBe TTSConfig(
        "elevenlabs",
        "eleven_multilingual_v2",
        "voice123",
        "el-1",
        "https://api.elevenlabs.io"
      )

      SpeechConfigLoader
        .tts(
          env(
            "SPEECH_TTS_MODEL"    -> "elevenlabs/voice123",
            "ELEVENLABS_API_KEY"  -> "el-1",
            "ELEVENLABS_MODEL_ID" -> "eleven_turbo_v2"
          )
        )
        .value
        .model shouldBe "eleven_turbo_v2"
    }

    "derive the Azure endpoint from the region" in {
      SpeechConfigLoader
        .tts(
          env(
            "SPEECH_TTS_MODEL"    -> "azure/en-US-JennyNeural",
            "AZURE_SPEECH_KEY"    -> "az-1",
            "AZURE_SPEECH_REGION" -> "westeurope"
          )
        )
        .value shouldBe TTSConfig(
        "azure",
        "en-US-JennyNeural",
        "en-US-JennyNeural",
        "az-1",
        "https://westeurope.tts.speech.microsoft.com",
        Some("westeurope")
      )
    }

    "let AZURE_SPEECH_TTS_BASE_URL override the Azure endpoint" in {
      SpeechConfigLoader
        .tts(
          env(
            "SPEECH_TTS_MODEL"          -> "azure/v",
            "AZURE_SPEECH_KEY"          -> "az-1",
            "AZURE_SPEECH_REGION"       -> "eastus",
            "AZURE_SPEECH_TTS_BASE_URL" -> "https://private.example/"
          )
        )
        .value
        .baseUrl shouldBe "https://private.example"
    }

    "name the missing key when the selected provider's credentials are absent" in {
      val missingModel = SpeechConfigLoader.tts(env()).left.value
      missingModel shouldBe a[ConfigurationError]
      missingModel.message should include("SPEECH_TTS_MODEL")

      SpeechConfigLoader.tts(env("SPEECH_TTS_MODEL" -> "openai/tts-1")).left.value.message should include(
        "OPENAI_API_KEY"
      )
      SpeechConfigLoader.tts(env("SPEECH_TTS_MODEL" -> "elevenlabs/v")).left.value.message should include(
        "ELEVENLABS_API_KEY"
      )
      SpeechConfigLoader
        .tts(env("SPEECH_TTS_MODEL" -> "azure/v", "AZURE_SPEECH_KEY" -> "az"))
        .left
        .value
        .message should include("AZURE_SPEECH_REGION")
    }

    "treat a blank key as missing" in {
      SpeechConfigLoader
        .tts(env("SPEECH_TTS_MODEL" -> "openai/tts-1", "OPENAI_API_KEY" -> "  "))
        .left
        .value
        .message should include("OPENAI_API_KEY")
    }

    "reject an unknown provider and a malformed spec" in {
      SpeechConfigLoader.tts(env("SPEECH_TTS_MODEL" -> "acme/x")).left.value.message should include("acme")
      SpeechConfigLoader.tts(env("SPEECH_TTS_MODEL" -> "no-slash")).left.value shouldBe a[ConfigurationError]
    }
  }

  "SpeechConfigLoader.stt" should {

    "select OpenAI" in {
      SpeechConfigLoader
        .stt(env("SPEECH_STT_MODEL" -> "openai/whisper-1", "OPENAI_API_KEY" -> "sk-1"))
        .value shouldBe STTConfig("openai", "whisper-1", "sk-1", "https://api.openai.com")
    }

    "use the Azure model part as the default language" in {
      SpeechConfigLoader
        .stt(
          env(
            "SPEECH_STT_MODEL"    -> "azure/de-DE",
            "AZURE_SPEECH_KEY"    -> "az-1",
            "AZURE_SPEECH_REGION" -> "eastus"
          )
        )
        .value shouldBe STTConfig(
        "azure",
        "de-DE",
        "az-1",
        "https://eastus.stt.speech.microsoft.com",
        Some("eastus")
      )
    }

    "name the missing key, and reject elevenlabs, which has no STT" in {
      SpeechConfigLoader.stt(env()).left.value.message should include("SPEECH_STT_MODEL")
      SpeechConfigLoader.stt(env("SPEECH_STT_MODEL" -> "openai/whisper-1")).left.value.message should include(
        "OPENAI_API_KEY"
      )
      SpeechConfigLoader
        .stt(env("SPEECH_STT_MODEL" -> "elevenlabs/x", "ELEVENLABS_API_KEY" -> "k"))
        .left
        .value
        .message should include("elevenlabs")
    }
  }

  "SpeechConfigLoader with an explicit model spec" should {

    "read only the credentials, not llm4s.speech.tts.model" in {
      SpeechConfigLoader
        .tts("openai/tts-1-hd", env("OPENAI_API_KEY" -> "sk-1", "SPEECH_TTS_MODEL" -> "azure/ignored"))
        .value shouldBe TTSConfig("openai", "tts-1-hd", "alloy", "sk-1", "https://api.openai.com")

      SpeechConfigLoader
        .stt("azure/fr-FR", env("AZURE_SPEECH_KEY" -> "az", "AZURE_SPEECH_REGION" -> "eastus"))
        .value
        .model shouldBe "fr-FR"
    }

    "fail with the missing key, or an unknown provider" in {
      SpeechConfigLoader.tts("openai/tts-1", env()).left.value.message should include("OPENAI_API_KEY")
      SpeechConfigLoader.stt("openai/whisper-1", env()).left.value.message should include("OPENAI_API_KEY")
      SpeechConfigLoader.tts("acme/x", env()).left.value.message should include("acme")
      SpeechConfigLoader.stt("elevenlabs/x", env("ELEVENLABS_API_KEY" -> "k")).left.value.message should include(
        "elevenlabs"
      )
    }
  }

  "SpeechProviderSelector.tts / stt" should {

    "build the client the configuration selects" in {
      SpeechProviderSelector
        .tts(env("SPEECH_TTS_MODEL" -> "elevenlabs/v", "ELEVENLABS_API_KEY" -> "k"))
        .value
        .name shouldBe "elevenlabs-tts"
      SpeechProviderSelector
        .stt(env("SPEECH_STT_MODEL" -> "openai/whisper-1", "OPENAI_API_KEY" -> "k"))
        .value
        .name shouldBe "openai-stt"
    }

    "surface a configuration error instead of a client" in {
      SpeechProviderSelector.tts(env()).left.value shouldBe a[ConfigurationError]
      SpeechProviderSelector.stt(env()).left.value shouldBe a[ConfigurationError]
    }
  }
}
