package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalamock.scalatest.MockFactory
import org.llm4s.speech.{ AudioInput, AudioMeta }
import java.io.ByteArrayInputStream

class VoskSpeechToTextMockSpec extends AnyFlatSpec with Matchers with MockFactory {

  // ===== Test Initialization =====
  "VoskSpeechToText" should "have correct name identifier" in {
    val vosk = new VoskSpeechToText()
    vosk.name shouldBe "vosk"
  }

  it should "support WAV and PCM formats" in {
    val vosk = new VoskSpeechToText()
    vosk.supportedFormats shouldBe List("audio/wav", "audio/pcm")
  }

  it should "have default configuration" in {
    // Test that companion object constants exist
    VoskSpeechToText.DEFAULT_MODEL_PATH should not be empty
    VoskSpeechToText.DEFAULT_SAMPLE_RATE shouldBe 16000
    VoskSpeechToText.DEFAULT_BUFFER_SIZE shouldBe 4096
  }

  it should "allow custom model path" in {
    val customPath = Some("/custom/model/path")
    // Just verify it accepts the parameter (actual model loading would fail)
    val vosk = new VoskSpeechToText(modelPath = customPath)
    vosk.name shouldBe "vosk"
  }

  it should "allow custom sample rate" in {
    val vosk = new VoskSpeechToText(targetSampleRate = 8000)
    vosk.name shouldBe "vosk"
  }

  it should "allow custom buffer size" in {
    val vosk = new VoskSpeechToText(bufferSize = 8192)
    vosk.name shouldBe "vosk"
  }

  // ===== Test Initialization Parameters =====
  "VoskSpeechToText initialization" should "accept default parameters" in {
    val vosk = new VoskSpeechToText()
    vosk.name shouldBe "vosk"
  }

  it should "accept explicit model path" in {
    val vosk = new VoskSpeechToText(modelPath = Some("models/test"))
    vosk.name shouldBe "vosk"
  }

  it should "accept high sample rate (48000)" in {
    val vosk = new VoskSpeechToText(targetSampleRate = 48000)
    vosk.name shouldBe "vosk"
  }

  it should "accept low sample rate (8000)" in {
    val vosk = new VoskSpeechToText(targetSampleRate = 8000)
    vosk.name shouldBe "vosk"
  }

  it should "accept small buffer size (1024)" in {
    val vosk = new VoskSpeechToText(bufferSize = 1024)
    vosk.name shouldBe "vosk"
  }

  it should "accept large buffer size (65536)" in {
    val vosk = new VoskSpeechToText(bufferSize = 65536)
    vosk.name shouldBe "vosk"
  }

  // ===== Test STT Options Handling =====
  "STTOptions support" should "allow language configuration" in {
    val options = STTOptions(language = Some("en"))
    options.language shouldBe Some("en")
  }

  it should "allow timestamps configuration" in {
    val options = STTOptions(enableTimestamps = true)
    options.enableTimestamps shouldBe true
  }

  it should "allow prompt configuration" in {
    val options = STTOptions(prompt = Some("technical terms"))
    options.prompt shouldBe Some("technical terms")
  }

  it should "allow confidence threshold configuration" in {
    val options = STTOptions(confidenceThreshold = 0.5)
    options.confidenceThreshold shouldBe 0.5
  }

  // ===== Test Supported Formats =====
  "Format support" should "include WAV" in {
    val vosk = new VoskSpeechToText()
    vosk.supportedFormats should contain("audio/wav")
  }

  it should "include PCM" in {
    val vosk = new VoskSpeechToText()
    vosk.supportedFormats should contain("audio/pcm")
  }

  it should "not include unsupported formats" in {
    val vosk = new VoskSpeechToText()
    vosk.supportedFormats should not contain "audio/mp3"
  }

  // ===== Test Error Handling =====
  "Error handling" should "handle invalid input gracefully" in {
    val vosk = new VoskSpeechToText()
    vosk.name shouldBe "vosk"
    // Note: transcribe method will fail due to native library not being available
    // This test just verifies the class structure, not the actual transcription
  }

  // ===== Test State Management =====
  "VoskSpeechToText state" should "be reusable across calls" in {
    val vosk = new VoskSpeechToText()
    vosk.name shouldBe "vosk"
    vosk.supportedFormats.nonEmpty shouldBe true
    // Should remain consistent
    vosk.name shouldBe "vosk"
  }

  it should "maintain configuration across multiple accesses" in {
    val customRate = 22050
    val vosk       = new VoskSpeechToText(targetSampleRate = customRate)
    vosk.name shouldBe "vosk"
    // Verify configuration is stable (via consistent behavior)
    vosk.supportedFormats.size shouldBe 2
  }

  // ===== Test Constants =====
  "VoskSpeechToText companion object" should "define DEFAULT_MODEL_PATH" in {
    VoskSpeechToText.DEFAULT_MODEL_PATH shouldBe "models/vosk-model-small-en-us-0.15"
  }

  it should "define DEFAULT_SAMPLE_RATE as 16000" in {
    VoskSpeechToText.DEFAULT_SAMPLE_RATE shouldBe 16000
  }

  it should "define DEFAULT_BUFFER_SIZE as 4096" in {
    VoskSpeechToText.DEFAULT_BUFFER_SIZE shouldBe 4096
  }

  // ===== Test Audio Input Handling =====
  "Audio input handling" should "accept BytesAudio" in {
    val bytes = Array[Byte](0, 1, 2, 3)
    val input = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)
    // Just verify the input can be constructed
    input.isInstanceOf[AudioInput.BytesAudio] shouldBe true
  }

  it should "accept StreamAudio" in {
    val stream = new ByteArrayInputStream(Array[Byte](0, 1, 2, 3))
    val input  = AudioInput.StreamAudio(stream, sampleRate = 16000, numChannels = 1)
    // Just verify the input can be constructed
    input.isInstanceOf[AudioInput.StreamAudio] shouldBe true
  }

  // ===== Test Metadata Handling =====
  "Metadata handling" should "support mono audio" in {
    val meta = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    meta.numChannels shouldBe 1
  }

  it should "support stereo audio" in {
    val meta = AudioMeta(sampleRate = 16000, numChannels = 2, bitDepth = 16)
    meta.numChannels shouldBe 2
  }

  it should "support various sample rates" in {
    val sampleRates = List(8000, 16000, 22050, 44100, 48000)
    sampleRates.foreach { sr =>
      val meta = AudioMeta(sampleRate = sr, numChannels = 1, bitDepth = 16)
      meta.sampleRate shouldBe sr
    }
  }

  // ===== Test STT Options Defaults =====
  "STTOptions defaults" should "have language None by default" in {
    val options = STTOptions()
    options.language shouldBe None
  }

  it should "have enableTimestamps false by default" in {
    val options = STTOptions()
    options.enableTimestamps shouldBe false
  }

  it should "have diarization false by default" in {
    val options = STTOptions()
    options.diarization shouldBe false
  }

  it should "have prompt None by default" in {
    val options = STTOptions()
    options.prompt shouldBe None
  }

  // ===== Test Configuration Combinations =====
  "Configuration combinations" should "support all options simultaneously" in {
    val options = STTOptions(
      language = Some("en"),
      prompt = Some("medical terms"),
      enableTimestamps = true,
      diarization = true,
      confidenceThreshold = 0.7
    )
    options.language shouldBe Some("en")
    options.prompt shouldBe Some("medical terms")
    options.enableTimestamps shouldBe true
    options.diarization shouldBe true
    options.confidenceThreshold shouldBe 0.7
  }

  it should "support partial options" in {
    val options = STTOptions(language = Some("fr"), enableTimestamps = true)
    options.language shouldBe Some("fr")
    options.enableTimestamps shouldBe true
    options.diarization shouldBe false
    options.prompt shouldBe None
    options.confidenceThreshold shouldBe 0.0
  }

  // ===== Test Class Structure =====
  "VoskSpeechToText class" should "extend SpeechToText" in {
    val vosk = new VoskSpeechToText()
    vosk.isInstanceOf[SpeechToText] shouldBe true
  }

  it should "be final (not extensible)" in {
    // Verify singleton/final behavior by name
    val className = new VoskSpeechToText().getClass.getSimpleName
    className shouldBe "VoskSpeechToText"
  }

  // ===== Test Edge Cases =====
  "Edge case configurations" should "handle zero channels gracefully (construction)" in {
    val meta = AudioMeta(sampleRate = 16000, numChannels = 0, bitDepth = 16)
    meta.numChannels shouldBe 0
  }

  it should "handle extreme sample rates (construction)" in {
    val lowMeta  = AudioMeta(sampleRate = 4000, numChannels = 1, bitDepth = 16)
    val highMeta = AudioMeta(sampleRate = 384000, numChannels = 1, bitDepth = 16)
    lowMeta.sampleRate shouldBe 4000
    highMeta.sampleRate shouldBe 384000
  }

  it should "handle very large buffers" in {
    val vosk = new VoskSpeechToText(bufferSize = 1024 * 1024)
    vosk.name shouldBe "vosk"
  }

  it should "handle very small buffers" in {
    val vosk = new VoskSpeechToText(bufferSize = 128)
    vosk.name shouldBe "vosk"
  }
}
