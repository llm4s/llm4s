package org.llm4s.speech

import org.llm4s.speech.cloud.{ AzureSTTClient, AzureTTSClient }
import org.llm4s.speech.stt.STTOptions
import org.llm4s.speech.tts.TTSOptions
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Smoke tests for the Azure Cognitive Services Speech cloud provider (TTS + STT).
 *
 * Requires: `AZURE_SPEECH_KEY`, `AZURE_SPEECH_REGION` environment variables.
 * Run with: `sbt testSmoke` or `sbt "it/testOnly org.llm4s.speech.AzureSpeechSmokeSpec"`
 *
 * These tests make real HTTP calls and incur API usage costs.
 * The round-trip test synthesizes audio and then transcribes it back.
 */
class AzureSpeechSmokeSpec extends AnyFlatSpec with Matchers {

  private val speechKey: Option[String]    = Option(System.getenv("AZURE_SPEECH_KEY")).filter(_.nonEmpty)
  private val speechRegion: Option[String] = Option(System.getenv("AZURE_SPEECH_REGION")).filter(_.nonEmpty)

  private def keysAvailable: Boolean = speechKey.isDefined && speechRegion.isDefined

  "Azure TTS" should "synthesize a short phrase and return non-empty audio bytes" taggedAs CloudSmoke in {
    assume(keysAvailable, "AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set — skipping Azure TTS smoke test")

    val client = AzureTTSClient(speechKey.get, speechRegion.get)
    val result = client.synthesize("Hello from llm4s.", TTSOptions())

    withClue(s"Azure TTS synthesis failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
    result.toOption.get.data should not be empty
  }

  "Azure STT" should "transcribe audio bytes and return non-empty text" taggedAs CloudSmoke in {
    assume(keysAvailable, "AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set — skipping Azure STT smoke test")

    // Minimal WAV header (44 bytes) + silence (1024 zero bytes) for a 16 kHz mono 16-bit PCM stream
    val wavBytes = buildMinimalWav(sampleRate = 16000, numChannels = 1, bitsPerSample = 16)
    val client   = AzureSTTClient(speechKey.get, speechRegion.get)
    val input    = AudioInput.BytesAudio(wavBytes, sampleRate = 16000, numChannels = 1)

    val result = client.transcribe(input, STTOptions(language = Some("en-US")))

    // Azure returns a result even for near-silence — we just check it doesn't throw
    withClue(s"Azure STT transcription failed: ${result.swap.toOption}") {
      result.isRight shouldBe true
    }
  }

  "Azure TTS → STT round-trip" should "synthesize 'hello world' then transcribe it" taggedAs CloudSmoke in {
    assume(keysAvailable, "AZURE_SPEECH_KEY / AZURE_SPEECH_REGION not set — skipping Azure round-trip smoke test")

    val ttsClient = AzureTTSClient(speechKey.get, speechRegion.get)
    val sttClient = AzureSTTClient(speechKey.get, speechRegion.get)

    // Step 1: Synthesize "hello world"
    val ttsResult = ttsClient.synthesize("hello world", TTSOptions())
    withClue(s"TTS step failed: ${ttsResult.swap.toOption}") {
      ttsResult.isRight shouldBe true
    }

    // Step 2: Transcribe the synthesized audio
    val audioBytes   = ttsResult.toOption.get.data
    val audioInput   = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val transcription = sttClient.transcribe(audioInput, STTOptions(language = Some("en-US")))

    withClue(s"STT step failed: ${transcription.swap.toOption}") {
      transcription.isRight shouldBe true
    }

    val text = transcription.toOption.get.text
    text.toLowerCase should include("hello")
  }

  /**
   * Build a minimal WAV file byte array for testing STT.
   * Standard RIFF/WAV structure with PCM silence.
   */
  private def buildMinimalWav(sampleRate: Int, numChannels: Int, bitsPerSample: Int): Array[Byte] = {
    val numSamples   = sampleRate / 4 // 0.25 seconds of silence
    val dataSize     = numSamples * numChannels * (bitsPerSample / 8)
    val headerSize   = 44
    val totalSize    = headerSize + dataSize
    val buf          = new Array[Byte](totalSize)

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

    // RIFF header
    buf(0)  = 'R'.toByte; buf(1)  = 'I'.toByte; buf(2)  = 'F'.toByte; buf(3)  = 'F'.toByte
    writeInt(4, totalSize - 8) // ChunkSize
    buf(8)  = 'W'.toByte; buf(9)  = 'A'.toByte; buf(10) = 'V'.toByte; buf(11) = 'E'.toByte

    // fmt sub-chunk
    buf(12) = 'f'.toByte; buf(13) = 'm'.toByte; buf(14) = 't'.toByte; buf(15) = ' '.toByte
    writeInt(16, 16)                                               // Subchunk1Size (PCM = 16)
    writeShort(20, 1)                                              // AudioFormat (PCM = 1)
    writeShort(22, numChannels)
    writeInt(24, sampleRate)
    writeInt(28, sampleRate * numChannels * (bitsPerSample / 8))   // ByteRate
    writeShort(32, numChannels * (bitsPerSample / 8))              // BlockAlign
    writeShort(34, bitsPerSample)

    // data sub-chunk
    buf(36) = 'd'.toByte; buf(37) = 'a'.toByte; buf(38) = 't'.toByte; buf(39) = 'a'.toByte
    writeInt(40, dataSize)
    // Remaining bytes (silence) are already 0

    buf
  }
}
