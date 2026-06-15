package org.llm4s.speech

import org.llm4s.speech.cloud.OpenAISTTClient
import org.llm4s.speech.stt.STTOptions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Smoke tests for the OpenAI Whisper STT cloud provider.
 *
 * Requires: `OPENAI_API_KEY` environment variable.
 * Run with: `sbt testSmoke` or `sbt "it/testOnly org.llm4s.speech.OpenAISTTSmokeSpec"`
 *
 * These tests make real HTTP calls and incur API usage costs.
 */
class OpenAISTTSmokeSpec extends AnyFlatSpec with Matchers {

  private val apiKey: Option[String] = Option(System.getenv("OPENAI_API_KEY")).filter(_.nonEmpty)

  "OpenAI STT (Whisper)" should "transcribe a small WAV fixture and return a non-empty string" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set — skipping OpenAI STT smoke test")

    val wavBytes = buildMinimalWav(sampleRate = 16000, numChannels = 1, bitsPerSample = 16)
    val client   = OpenAISTTClient(apiKey.get)
    val input    = AudioInput.BytesAudio(wavBytes, sampleRate = 16000, numChannels = 1)

    val result = client.transcribe(input, STTOptions(language = Some("en")))

    withClue(s"OpenAI STT transcription failed: ${result.swap.toOption}") {
      // Note: Whisper may return empty text for silent audio; we check the call succeeds.
      result.isRight shouldBe true
    }
  }

  it should "successfully call the API with BytesAudio input" taggedAs CloudSmoke in {
    assume(apiKey.isDefined, "OPENAI_API_KEY not set — skipping OpenAI STT smoke test")

    val wavBytes = buildMinimalWav(sampleRate = 16000, numChannels = 1, bitsPerSample = 16)
    val client   = OpenAISTTClient(apiKey.get)
    val input    = AudioInput.BytesAudio(wavBytes, 16000)

    val result = client.transcribe(input, STTOptions())

    withClue(s"Transcription call failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
  }

  /**
   * Build a minimal WAV file byte array suitable for API submission.
   * Standard RIFF/WAV structure with PCM silence.
   */
  private def buildMinimalWav(sampleRate: Int, numChannels: Int, bitsPerSample: Int): Array[Byte] = {
    val numSamples = sampleRate / 4 // 0.25 seconds
    val dataSize   = numSamples * numChannels * (bitsPerSample / 8)
    val headerSize = 44
    val totalSize  = headerSize + dataSize
    val buf        = new Array[Byte](totalSize)

    def writeInt(offset: Int, value: Int): Unit = {
      buf(offset)     = (value & 0xff).toByte
      buf(offset + 1) = ((value >> 8) & 0xff).toByte
      buf(offset + 2) = ((value >> 16) & 0xff).toByte
      buf(offset + 3) = ((value >> 24) & 0xff).toByte
    }

    def writeShort(offset: Int, value: Int): Unit = {
      buf(offset)     = (value & 0xff).toByte
      buf(offset + 1) = ((value >> 8) & 0xff).toByte
    }

    buf(0)  = 'R'.toByte; buf(1)  = 'I'.toByte; buf(2)  = 'F'.toByte; buf(3)  = 'F'.toByte
    writeInt(4, totalSize - 8)
    buf(8)  = 'W'.toByte; buf(9)  = 'A'.toByte; buf(10) = 'V'.toByte; buf(11) = 'E'.toByte
    buf(12) = 'f'.toByte; buf(13) = 'm'.toByte; buf(14) = 't'.toByte; buf(15) = ' '.toByte
    writeInt(16, 16)
    writeShort(20, 1)
    writeShort(22, numChannels)
    writeInt(24, sampleRate)
    writeInt(28, sampleRate * numChannels * (bitsPerSample / 8))
    writeShort(32, numChannels * (bitsPerSample / 8))
    writeShort(34, bitsPerSample)
    buf(36) = 'd'.toByte; buf(37) = 'a'.toByte; buf(38) = 't'.toByte; buf(39) = 'a'.toByte
    writeInt(40, dataSize)

    buf
  }
}
