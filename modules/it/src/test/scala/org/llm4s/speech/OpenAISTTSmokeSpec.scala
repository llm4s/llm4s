package org.llm4s.speech

import org.llm4s.error.AuthenticationError
import org.llm4s.it.Tier
import org.llm4s.it.tags.Cloud
import org.llm4s.speech.config.SpeechConfigLoader
import org.llm4s.speech.stt.STTOptions
import org.llm4s.speech.stt.provider.OpenAISTTClient
import org.llm4s.speech.tts.provider.OpenAITTSClient
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Cloud smoke test for OpenAI speech-to-text (Whisper).
 *
 * The audio is spoken by OpenAI TTS from a known phrase, so what the transcription must contain
 * is known and the test can fail: an empty, garbled or unrelated transcript does not contain
 * "hello world". (No audio fixture is checked in; a recorded clip would add a binary file for what
 * a synthesised phrase proves just as well.)
 *
 * Requires `OPENAI_API_KEY`, read through `SpeechConfigLoader`. Makes real, billed API calls.
 * Tier: `@Cloud` - `sbt testSmoke`.
 */
@Cloud
class OpenAISTTSmokeSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val ttsConfig = SpeechConfigLoader.tts("openai/tts-1")
  private val sttConfig = SpeechConfigLoader.stt("openai/whisper-1")

  private def spoken: Array[Byte] = {
    Tier.require(ttsConfig.isRight, "OPENAI_API_KEY not set")
    val audio = new OpenAITTSClient(ttsConfig.value).synthesize(CloudSpeechSmoke.Phrase).value
    CloudSpeechSmoke.wav16k(audio)
  }

  private def client: OpenAISTTClient = {
    Tier.require(sttConfig.isRight, "OPENAI_API_KEY not set")
    new OpenAISTTClient(sttConfig.value)
  }

  "OpenAI STT" should "transcribe a WAV it was given as bytes" in {
    val transcription = client.transcribe(AudioInput.BytesAudio(spoken, 16000)).value

    transcription.text.toLowerCase should include("hello world")
    transcription.text.toLowerCase should include("speech module")
  }

  it should "return ordered word timestamps inside the audio when asked" in {
    val wav           = spoken
    val seconds       = (wav.length - 44) / (16000.0 * 2)
    val transcription = client.transcribe(AudioInput.BytesAudio(wav, 16000), STTOptions(enableTimestamps = true)).value

    transcription.timestamps should not be empty
    transcription.timestamps.head.word.toLowerCase should startWith("hello")
    transcription.timestamps.map(_.startSec) shouldBe transcription.timestamps.map(_.startSec).sorted
    transcription.timestamps.last.endSec should ((be > 1.0).and(be <= seconds + 1.0))
  }

  it should "transcribe a WAV file" in {
    val file = java.nio.file.Files.createTempFile("llm4s-openai-stt-smoke-", ".wav")
    try {
      java.nio.file.Files.write(file, spoken)

      client.transcribe(AudioInput.FileAudio(file)).value.text.toLowerCase should include("hello world")
    } finally java.nio.file.Files.deleteIfExists(file)
  }

  it should "reject an invalid key with an authentication error" in {
    Tier.require(sttConfig.isRight, "OPENAI_API_KEY not set")
    val rejected = new OpenAISTTClient(sttConfig.value.copy(apiKey = "sk-invalid-llm4s-smoke-test"))

    rejected.transcribe(AudioInput.BytesAudio(spoken, 16000)).left.value shouldBe an[AuthenticationError]
  }
}
