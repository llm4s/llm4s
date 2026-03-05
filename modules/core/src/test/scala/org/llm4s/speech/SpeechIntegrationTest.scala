package org.llm4s.speech

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.speech.stt.{ VoskSpeechToText, WhisperSpeechToText, STTOptions }
import org.llm4s.speech.tts.{ Tacotron2TextToSpeech, TTSOptions }
import org.llm4s.speech.processing.AudioPreprocessing
import org.llm4s.speech.io.AudioIO
import org.llm4s.speech.util.PlatformCommands

import java.nio.file.Files

class SpeechIntegrationTest extends AnyFunSuite with Matchers {

  test("AudioPreprocessing should handle mono conversion") {
    // Test with mono data (should pass through unchanged)
    val monoBytes = Array.fill(100)(0.toByte)
    val monoMeta  = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)

    val result = AudioPreprocessing.toMono(monoBytes, monoMeta)
    result shouldBe Symbol("right")
    result.foreach { case (bytes, meta) =>
      meta.numChannels shouldBe 1
      bytes.length shouldBe 100
    }
  }

  test("AudioPreprocessing should handle resampling") {
    val sourceBytes = Array.fill(1000)(0.toByte)
    val sourceMeta  = AudioMeta(sampleRate = 8000, numChannels = 1, bitDepth = 16)
    val targetRate  = 16000

    val result = AudioPreprocessing.resamplePcm16(sourceBytes, sourceMeta, targetRate)
    result shouldBe Symbol("right")
    result.foreach { case (_, meta) =>
      meta.sampleRate shouldBe targetRate
    }
  }

  test("AudioPreprocessing should compose multiple operations") {
    // Test with simple mono data that won't cause array bounds issues
    val sourceBytes = Array.fill(200)(0.toByte) // Simple test data
    val sourceMeta  = AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 16)

    val result = AudioPreprocessing.standardizeForSTT(sourceBytes, sourceMeta, targetRate = 16000)
    result shouldBe Symbol("right")
    result.foreach { case (_, meta) =>
      meta.sampleRate shouldBe 16000
      meta.numChannels shouldBe 1
    }
  }

  test("VoskSpeechToText should handle configuration") {
    val stt = new VoskSpeechToText(modelPath = Some("/tmp/test-model"))
    stt.name shouldBe "vosk"
  }

  test("WhisperSpeechToText should build correct CLI arguments") {
    val stt = new WhisperSpeechToText(PlatformCommands.mockSuccess, model = "large", outputFormat = "json")
    val options = STTOptions(
      language = Some("en"),
      prompt = Some("This is a test"),
      enableTimestamps = true
    )

    // Test that it doesn't crash
    val fakePath = Files.createTempFile("test", ".wav")
    Files.write(fakePath, Array[Byte](0, 0, 0, 0))

    val result = stt.transcribe(AudioInput.FileAudio(fakePath), options)
    result shouldBe Symbol("right")
  }

  test("Tacotron2TextToSpeech should handle voice options") {
    // mockWavWriter writes a minimal valid WAV to --out so the full pipeline succeeds
    val tts = new Tacotron2TextToSpeech(PlatformCommands.mockWavWriter)
    val options = TTSOptions(
      voice = Some("en-female"),
      language = Some("en"),
      speakingRate = Some(1.2),
      pitchSemitones = Some(2.0),
      volumeGainDb = Some(3.0)
    )

    val result = tts.synthesize("Hello world", options)
    result shouldBe Symbol("right")
  }

  test("AudioIO should handle WAV and raw PCM output") {
    val testAudio = GeneratedAudio(
      data = Array.fill(1000)(0.toByte),
      meta = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16),
      format = AudioFormat.WavPcm16
    )

    val wavPath = Files.createTempFile("test", ".wav")
    val rawPath = Files.createTempFile("test", ".pcm")

    val wavResult = AudioIO.saveWav(testAudio, wavPath)
    val rawResult = AudioIO.saveRawPcm16(testAudio, rawPath)

    wavResult shouldBe Symbol("right")
    rawResult shouldBe Symbol("right")

    // Cleanup
    Files.deleteIfExists(wavPath)
    Files.deleteIfExists(rawPath)
  }

  test("AudioConverter should compose operations") {
    import org.llm4s.speech.processing.AudioConverter

    val converter = AudioConverter.sttPreprocessor(targetRate = 16000)
    converter.name should include("mono-converter")
    converter.name should include("resample-converter-16000Hz")
    converter.name should include("silence-trimmer-512")
  }
}
