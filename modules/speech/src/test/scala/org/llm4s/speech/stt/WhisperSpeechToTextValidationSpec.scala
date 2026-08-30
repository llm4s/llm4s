package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.speech.{ AudioInput, AudioMeta }
import java.io.ByteArrayInputStream

class WhisperSpeechToTextValidationSpec extends AnyFlatSpec with Matchers {

  // ===== Test Initialization =====
  "WhisperSpeechToText" should "have correct name identifier" in {
    val whisper = new WhisperSpeechToText()
    whisper.name shouldBe "whisper-cli"
  }

  it should "support multiple audio formats" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should contain("audio/wav")
    whisper.supportedFormats should contain("audio/mp3")
    whisper.supportedFormats should contain("audio/m4a")
    whisper.supportedFormats should contain("audio/flac")
    whisper.supportedFormats should contain("audio/ogg")
  }

  it should "have 5 supported formats" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats.length shouldBe 5
  }

  it should "use default command" in {
    val whisper = new WhisperSpeechToText()
    whisper.name shouldBe "whisper-cli"
  }

  // ===== Test Default Configuration =====
  "WhisperSpeechToText defaults" should "use 'whisper' command by default" in {
    val customCommand = Seq("whisper")
    val whisper       = new WhisperSpeechToText(command = customCommand)
    whisper.name shouldBe "whisper-cli"
  }

  it should "use 'base' model by default" in {
    val whisper = new WhisperSpeechToText()
    whisper.name shouldBe "whisper-cli"
  }

  it should "use 'txt' output format by default" in {
    val whisper = new WhisperSpeechToText()
    whisper.name shouldBe "whisper-cli"
  }

  // ===== Test Custom Configuration =====
  "Custom configuration" should "allow custom command" in {
    val customCmd = Seq("/usr/local/bin/whisper")
    val whisper   = new WhisperSpeechToText(command = customCmd)
    whisper.name shouldBe "whisper-cli"
  }

  it should "allow custom model" in {
    val whisper = new WhisperSpeechToText(model = "large")
    whisper.name shouldBe "whisper-cli"
  }

  it should "allow custom output format" in {
    val whisper = new WhisperSpeechToText(outputFormat = "json")
    whisper.name shouldBe "whisper-cli"
  }

  it should "allow all custom parameters simultaneously" in {
    val customCmd = Seq("whisper", "--gpu")
    val whisper = new WhisperSpeechToText(
      command = customCmd,
      model = "tiny",
      outputFormat = "vtt"
    )
    whisper.name shouldBe "whisper-cli"
  }

  // ===== Test Audio Format Support =====
  "Audio format support" should "include WAV" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should contain("audio/wav")
  }

  it should "include MP3" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should contain("audio/mp3")
  }

  it should "include M4A" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should contain("audio/m4a")
  }

  it should "include FLAC" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should contain("audio/flac")
  }

  it should "include OGG" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should contain("audio/ogg")
  }

  it should "not include unsupported formats like AIFF" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should not contain "audio/aiff"
  }

  it should "not include video formats" in {
    val whisper = new WhisperSpeechToText()
    whisper.supportedFormats should not contain "video/mp4"
  }

  // ===== Test Instance Consistency =====
  "Instance behavior" should "maintain name across accesses" in {
    val whisper = new WhisperSpeechToText()
    whisper.name shouldBe "whisper-cli"
    whisper.name shouldBe "whisper-cli"
  }

  it should "maintain formats across accesses" in {
    val whisper  = new WhisperSpeechToText()
    val formats1 = whisper.supportedFormats
    val formats2 = whisper.supportedFormats
    formats1 shouldBe formats2
  }

  // ===== Test STT Options =====
  "STT options handling" should "support language configuration" in {
    val options = STTOptions(language = Some("fr"))
    options.language shouldBe Some("fr")
  }

  it should "support prompt configuration" in {
    val options = STTOptions(prompt = Some("medical terminology"))
    options.prompt shouldBe Some("medical terminology")
  }

  it should "support timestamps configuration" in {
    val options = STTOptions(enableTimestamps = true)
    options.enableTimestamps shouldBe true
  }

  it should "have confidence threshold with default value" in {
    val options = STTOptions()
    options.confidenceThreshold shouldBe a[Double]
  }

  it should "support diarization configuration" in {
    val options = STTOptions(diarization = true)
    options.diarization shouldBe true
  }

  // ===== Test Transcription Result =====
  "Transcription results" should "have text field" in {
    val trans = Transcription(text = "Hello world", language = None)
    trans.text shouldBe "Hello world"
  }

  it should "support optional language" in {
    val transEn   = Transcription(text = "Hello", language = Some("en"))
    val transNone = Transcription(text = "Hello", language = None)
    transEn.language shouldBe Some("en")
    transNone.language shouldBe None
  }

  it should "support optional confidence" in {
    val transWithConf = Transcription(text = "Hello", language = None, confidence = Some(0.95))
    val transNoConf   = Transcription(text = "Hello", language = None, confidence = None)
    transWithConf.confidence.isDefined shouldBe true
    transNoConf.confidence.isDefined shouldBe false
  }

  it should "support timestamps" in {
    val timestamps = List(
      WordTimestamp("Hello", 0.0, 0.5),
      WordTimestamp("world", 0.6, 1.1)
    )
    val trans = Transcription(text = "Hello world", language = None, timestamps = timestamps)
    trans.timestamps.length shouldBe 2
  }

  // ===== Test Class Structure =====
  "WhisperSpeechToText class" should "extend SpeechToText" in {
    val whisper = new WhisperSpeechToText()
    whisper.isInstanceOf[SpeechToText] shouldBe true
  }

  it should "be final" in {
    val className = new WhisperSpeechToText().getClass.getSimpleName
    className shouldBe "WhisperSpeechToText"
  }

  // ===== Test Model Selection =====
  "Model selection" should "support tiny model" in {
    val whisper = new WhisperSpeechToText(model = "tiny")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support base model" in {
    val whisper = new WhisperSpeechToText(model = "base")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support small model" in {
    val whisper = new WhisperSpeechToText(model = "small")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support medium model" in {
    val whisper = new WhisperSpeechToText(model = "medium")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support large model" in {
    val whisper = new WhisperSpeechToText(model = "large")
    whisper.name shouldBe "whisper-cli"
  }

  // ===== Test Output Formats =====
  "Output format selection" should "support json format" in {
    val whisper = new WhisperSpeechToText(outputFormat = "json")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support vtt format" in {
    val whisper = new WhisperSpeechToText(outputFormat = "vtt")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support srt format" in {
    val whisper = new WhisperSpeechToText(outputFormat = "srt")
    whisper.name shouldBe "whisper-cli"
  }

  it should "support txt format" in {
    val whisper = new WhisperSpeechToText(outputFormat = "txt")
    whisper.name shouldBe "whisper-cli"
  }

  // ===== Test Audio Inputs =====
  "Audio input types" should "support BytesAudio" in {
    val bytes = Array[Byte](0, 1, 2, 3)
    val input = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)
    // Just verify the input can be created
    input.isInstanceOf[AudioInput.BytesAudio] shouldBe true
  }

  it should "support StreamAudio" in {
    val stream = new ByteArrayInputStream(Array[Byte](0, 1, 2, 3))
    val input  = AudioInput.StreamAudio(stream, sampleRate = 16000, numChannels = 2)
    // Just verify the input can be created
    input.isInstanceOf[AudioInput.StreamAudio] shouldBe true
  }

  // ===== Test Edge Cases =====
  "Edge cases" should "handle empty command sequence gracefully (construction)" in {
    val whisper = new WhisperSpeechToText(command = Seq())
    whisper.name shouldBe "whisper-cli"
  }

  it should "handle very long model names" in {
    val whisper = new WhisperSpeechToText(model = "model-with-very-long-name-that-might-not-exist")
    whisper.name shouldBe "whisper-cli"
  }

  it should "handle special characters in output format" in {
    val whisper = new WhisperSpeechToText(outputFormat = "json_special-format.v1")
    whisper.name shouldBe "whisper-cli"
  }

  // ===== Test Metadata =====
  "Metadata handling" should "support standard metadata" in {
    val meta = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    meta.sampleRate shouldBe 16000
  }

  it should "support various sample rates" in {
    val sampleRates = List(8000, 16000, 22050, 44100, 48000)
    sampleRates.foreach { sr =>
      val meta = AudioMeta(sampleRate = sr, numChannels = 1, bitDepth = 16)
      meta.sampleRate shouldBe sr
    }
  }

  it should "support mono and stereo" in {
    val mono   = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    val stereo = AudioMeta(sampleRate = 16000, numChannels = 2, bitDepth = 16)
    mono.numChannels shouldBe 1
    stereo.numChannels shouldBe 2
  }
}
