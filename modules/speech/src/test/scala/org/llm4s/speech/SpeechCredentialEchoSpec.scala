package org.llm4s.speech

import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
final class SpeechCredentialEchoSpec extends AnyFlatSpec with Matchers {

  private val echoKey = "echo-key-0123456789abcdef"

  private val pcm = Array.fill[Byte](3200)(7)
  private val wav =
    WavFileGenerator.createWavHeader(pcm.length, AudioMeta(16000, 1, 16)).toOption.get ++ pcm
  private def audio = AudioInput.BytesAudio(wav, 16000)

  private def tts(provider: String, baseUrl: String) =
    TTSConfig(provider, "model", "voice", Secret(echoKey), baseUrl, Some("eastus"))
  private def stt(provider: String, baseUrl: String) =
    STTConfig(provider, "model", Secret(echoKey), baseUrl, Some("eastus"))

  "OpenAITTSClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey)(url => new OpenAITTSClient(tts("openai", url)).synthesize("Hello"))
  }

  "ElevenLabsTTSClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey)(url =>
      new ElevenLabsTTSClient(tts("elevenlabs", url)).synthesize("Hello")
    )
  }

  "AzureTTSClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey)(url => new AzureTTSClient(tts("azure", url)).synthesize("Hello"))
  }

  "OpenAISTTClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey)(url => new OpenAISTTClient(stt("openai", url)).transcribe(audio))
  }

  "AzureSTTClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey)(url => new AzureSTTClient(stt("azure", url)).transcribe(audio))
  }
}
