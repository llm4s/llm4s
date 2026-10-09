package org.llm4s.speech

import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.llm4s.testkit.{ LocalProviderTestServer, ProviderModuleChecks }
import org.scalatest.flatspec.AnyFlatSpec

/**
 * Every cloud speech client honours interruption (design section 4.4): called on a virtual thread
 * against a server that never answers, then interrupted, it returns `Left(CancelledError)` promptly
 * with the thread's interrupt flag still set - not a transport failure, and no exception.
 */
class CloudSpeechCancellationSpec extends AnyFlatSpec {

  private val audio = AudioInput.BytesAudio(Array.fill[Byte](200)(1), 16000)

  /** Runs `test` with the base URL of a server that holds every request open. */
  private def withHeldServer(test: String => Any): Unit =
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen)(test)

  "AzureSTTClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new AzureSTTClient(STTConfig("azure", "en-US", "azure-key", url, Some("eastus")))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("azure-stt transcribe")(client.transcribe(audio))
  }

  "OpenAISTTClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new OpenAISTTClient(STTConfig("openai", "whisper-1", "sk-test-key", url))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("openai-stt transcribe")(client.transcribe(audio))
  }

  "AzureTTSClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new AzureTTSClient(TTSConfig("azure", "azure", "en-US-JennyNeural", "azure-key", url, Some("eastus")))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("azure-tts synthesize")(client.synthesize("Hello"))
  }

  "ElevenLabsTTSClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new ElevenLabsTTSClient(TTSConfig("elevenlabs", "eleven_multilingual_v2", "voice123", "el-key", url))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("elevenlabs-tts synthesize")(client.synthesize("Hello"))
  }

  "OpenAITTSClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new OpenAITTSClient(TTSConfig("openai", "tts-1", "alloy", "sk-test-key", url))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("openai-tts synthesize")(client.synthesize("Hello"))
  }
}
