package org.llm4s.speech.config

import org.llm4s.config.ReferenceConfig
import org.llm4s.error.{ ConfigurationError, ValidationError }
import org.llm4s.speech.{ SpeechProviderSelector, StubHttpClient }
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.scalatest.EitherValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** Model-spec parsing and the "only the selected provider's credentials" rule, as a matrix. */
class SpeechConfigMatrixSpec extends AnyWordSpec with Matchers with EitherValues {

  private def env(pairs: (String, String)*) = ReferenceConfig.withEnv("", pairs.toMap)

  private val allKeys = Seq(
    "OPENAI_API_KEY"      -> "sk-o",
    "ELEVENLABS_API_KEY"  -> "sk-e",
    "AZURE_SPEECH_KEY"    -> "az-k",
    "AZURE_SPEECH_REGION" -> "westeurope"
  )

  "SpeechProviderSelector.parseModelSpec" should {
    "accept provider/model, lower-casing the provider and trimming" in {
      val table = Seq(
        "openai/tts-1"                 -> (("openai", "tts-1")),
        "OpenAI/tts-1"                 -> (("openai", "tts-1")),
        "  azure / en-US-JennyNeural " -> (("azure", "en-US-JennyNeural")),
        "elevenlabs/abc/def"           -> (("elevenlabs", "abc/def")),
        "openai/Tts-1-HD"              -> (("openai", "Tts-1-HD"))
      )
      table.foreach { case (in, out) => withClue(in)(SpeechProviderSelector.parseModelSpec(in).value shouldBe out) }
    }

    "reject malformed specs with a ConfigurationError that names the input" in {
      Seq("", " ", "openai", "openai/", "/tts-1", " / ", "openai/ ").foreach { in =>
        withClue(s"'$in'") {
          val e = SpeechProviderSelector.parseModelSpec(in).left.value
          e shouldBe a[ConfigurationError]
          e.message should include("provider/model")
        }
      }
    }
  }

  "SpeechConfigLoader with every provider's credentials present" should {
    "return only the selected provider's key" in {
      val tts = SpeechConfigLoader.tts(env(allKeys :+ ("SPEECH_TTS_MODEL" -> "elevenlabs/v1"): _*)).value
      tts.apiKey shouldBe "sk-e"
      tts.provider shouldBe "elevenlabs"
      SpeechConfigLoader.stt(env(allKeys :+ ("SPEECH_STT_MODEL" -> "azure/de-DE"): _*)).value.apiKey shouldBe "az-k"
    }
  }

  "SpeechConfigLoader requiring only the selected provider's credentials" should {
    "not need the other providers' keys (TTS)" in {
      val cases = Seq(
        "openai/tts-1"  -> Seq("OPENAI_API_KEY" -> "k"),
        "elevenlabs/v"  -> Seq("ELEVENLABS_API_KEY" -> "k"),
        "azure/en-US-A" -> Seq("AZURE_SPEECH_KEY" -> "k", "AZURE_SPEECH_REGION" -> "eastus")
      )
      cases.foreach { case (spec, creds) =>
        withClue(spec)(SpeechConfigLoader.tts(env(creds :+ ("SPEECH_TTS_MODEL" -> spec): _*)).isRight shouldBe true)
      }
    }

    "fail naming the missing variable for each provider, ignoring other providers' keys" in {
      val cases = Seq(
        ("openai/tts-1", "OPENAI_API_KEY", Seq("ELEVENLABS_API_KEY" -> "k", "AZURE_SPEECH_KEY" -> "k")),
        ("elevenlabs/v", "ELEVENLABS_API_KEY", Seq("OPENAI_API_KEY" -> "k", "AZURE_SPEECH_KEY" -> "k")),
        ("azure/en-US-A", "AZURE_SPEECH_KEY", Seq("OPENAI_API_KEY" -> "k", "AZURE_SPEECH_REGION" -> "r")),
        ("azure/en-US-A", "AZURE_SPEECH_REGION", Seq("OPENAI_API_KEY" -> "k", "AZURE_SPEECH_KEY" -> "k"))
      )
      cases.foreach { case (spec, missing, others) =>
        withClue(s"$spec missing $missing") {
          val e = SpeechConfigLoader.tts(env(others :+ ("SPEECH_TTS_MODEL" -> spec): _*)).left.value
          e shouldBe a[ConfigurationError]
          e.message should include(missing)
        }
      }
    }

    "fail for STT azure without a region and for a blank model" in {
      SpeechConfigLoader
        .stt(env("SPEECH_STT_MODEL" -> "azure/en-US", "AZURE_SPEECH_KEY" -> "k"))
        .left
        .value
        .message should include("AZURE_SPEECH_REGION")
      SpeechConfigLoader
        .stt(env("SPEECH_STT_MODEL" -> "  ", "OPENAI_API_KEY" -> "k"))
        .left
        .value
        .message should include(
        "SPEECH_STT_MODEL"
      )
    }
  }

  "SpeechConfigLoader base URLs" should {
    "strip trailing slashes and whitespace from overrides" in {
      SpeechConfigLoader
        .tts(
          env(
            "SPEECH_TTS_MODEL"       -> "openai/tts-1",
            "OPENAI_API_KEY"         -> "k",
            "OPENAI_SPEECH_BASE_URL" -> " https://proxy.example/ "
          )
        )
        .value
        .baseUrl shouldBe "https://proxy.example"
    }
  }

  "Cloud config toString" should {
    "never contain the key" in {
      val tts = TTSConfig("openai", "tts-1", "alloy", "sk-very-secret", "https://x")
      val stt = STTConfig("openai", "whisper-1", "sk-very-secret", "https://x")
      (tts.toString should not).include("sk-very-secret")
      (stt.toString should not).include("sk-very-secret")
      (tts.copy(region = Some("r")).toString should not).include("sk-very-secret")
    }

    "not appear in the toString of a ConfigurationError for a missing key" in {
      val e = SpeechConfigLoader
        .tts(env("SPEECH_TTS_MODEL" -> "azure/v", "AZURE_SPEECH_KEY" -> "azure-secret-key"))
        .left
        .value
      (e.toString should not).include("azure-secret-key")
    }
  }

  "Every cloud TTS client" should {
    "reject blank, whitespace-only and newline-only text without sending a request" in {
      val cfg = TTSConfig("p", "m", "en-US-A", "k", "http://unused", Some("r"))
      val clients: Seq[(String, StubHttpClient => org.llm4s.speech.tts.TextToSpeech)] = Seq(
        "openai"     -> (h => new OpenAITTSClient(cfg, h)),
        "elevenlabs" -> (h => new ElevenLabsTTSClient(cfg, h)),
        "azure"      -> (h => new AzureTTSClient(cfg, h))
      )
      for {
        (n, mk) <- clients
        text    <- Seq("", " ", "\n\t \r\n")
      } {
        val http = new StubHttpClient(body = Array[Byte](1))
        withClue(s"$n '$text'")(mk(http).synthesize(text, TTSOptions()).left.value shouldBe a[ValidationError])
        http.requests shouldBe empty
      }
    }
  }
}
