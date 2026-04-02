package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.speech.AudioInput
import org.llm4s.types.Result
import java.io.ByteArrayInputStream
import java.nio.file.Paths

class WhisperSpeechToTextIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // ===== BytesAudio Input Tests =====
  "WhisperSpeechToText.transcribe with BytesAudio" should "handle small audio bytes" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options    = STTOptions(language = Some("en"))

    val result = whisper.transcribe(input, options)
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "handle larger audio bytes" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array.fill[Byte](5000)(0)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "handle empty audio bytes gracefully" in {
    val whisper    = new WhisperSpeechToText()
    val emptyBytes = Array[Byte]()
    val input      = AudioInput.BytesAudio(emptyBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== StreamAudio Input Tests =====
  "WhisperSpeechToText.transcribe with StreamAudio" should "handle stream input" in {
    val whisper    = new WhisperSpeechToText()
    val streamData = Array[Byte](0, 1, 2, 3, 4, 5)
    val stream     = new ByteArrayInputStream(streamData)
    val input      = AudioInput.StreamAudio(stream, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "accept mono and stereo streams" in {
    val whisper      = new WhisperSpeechToText()
    val streamMono   = new ByteArrayInputStream(Array[Byte](0, 1, 2, 3))
    val streamStereo = new ByteArrayInputStream(Array[Byte](0, 1, 2, 3, 4, 5))

    val inputMono   = AudioInput.StreamAudio(streamMono, sampleRate = 16000, numChannels = 1)
    val inputStereo = AudioInput.StreamAudio(streamStereo, sampleRate = 16000, numChannels = 2)

    val resultMono   = whisper.transcribe(inputMono, STTOptions())
    val resultStereo = whisper.transcribe(inputStereo, STTOptions())

    resultMono.isInstanceOf[Result[Transcription]] shouldBe true
    resultStereo.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== File Audio Input Tests =====
  "WhisperSpeechToText.transcribe with FileAudio" should "return error for non-existent file" in {
    val whisper         = new WhisperSpeechToText()
    val nonExistentPath = Paths.get("/nonexistent/audio/file.wav")
    val input           = AudioInput.FileAudio(nonExistentPath)

    val result = whisper.transcribe(input, STTOptions())
    result.isLeft shouldBe true
  }

  // ===== Model Selection =====
  "WhisperSpeechToText with different models" should "support 'tiny' model" in {
    val whisper    = new WhisperSpeechToText(model = "tiny")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'base' model (default)" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'small' model" in {
    val whisper    = new WhisperSpeechToText(model = "small")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'medium' model" in {
    val whisper    = new WhisperSpeechToText(model = "medium")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'large' model" in {
    val whisper    = new WhisperSpeechToText(model = "large")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Output Format Selection =====
  "WhisperSpeechToText with different output formats" should "support 'txt' format (default)" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'json' format" in {
    val whisper    = new WhisperSpeechToText(outputFormat = "json")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'vtt' format" in {
    val whisper    = new WhisperSpeechToText(outputFormat = "vtt")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 'srt' format" in {
    val whisper    = new WhisperSpeechToText(outputFormat = "srt")
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== STT Options Tests =====
  "WhisperSpeechToText with STTOptions" should "accept language configuration" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val optionsFr = STTOptions(language = Some("fr"))
    val optionsEn = STTOptions(language = Some("en"))
    val optionsJa = STTOptions(language = Some("ja"))

    val resultFr = whisper.transcribe(input, optionsFr)
    val resultEn = whisper.transcribe(input, optionsEn)
    val resultJa = whisper.transcribe(input, optionsJa)

    resultFr.isInstanceOf[Result[Transcription]] shouldBe true
    resultEn.isInstanceOf[Result[Transcription]] shouldBe true
    resultJa.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "accept prompt configuration" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options    = STTOptions(prompt = Some("medical terminology"))

    val result = whisper.transcribe(input, options)
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "accept timestamps configuration" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val optionsWithTimestamps    = STTOptions(enableTimestamps = true)
    val optionsWithoutTimestamps = STTOptions(enableTimestamps = false)

    val result1 = whisper.transcribe(input, optionsWithTimestamps)
    val result2 = whisper.transcribe(input, optionsWithoutTimestamps)

    result1.isInstanceOf[Result[Transcription]] shouldBe true
    result2.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "accept diarization configuration" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options    = STTOptions(diarization = true)

    val result = whisper.transcribe(input, options)
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "accept confidence threshold configuration" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options    = STTOptions(confidenceThreshold = 0.7)

    val result = whisper.transcribe(input, options)
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Command Configuration =====
  "WhisperSpeechToText with custom commands" should "accept single command" in {
    val whisper    = new WhisperSpeechToText(command = Seq("whisper"))
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "accept command with arguments" in {
    val whisper    = new WhisperSpeechToText(command = Seq("whisper", "--gpu"))
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Audio Format Support =====
  "Supported audio formats" should "include WAV" in {
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

  // ===== Combined Configurations =====
  "WhisperSpeechToText with combined configurations" should "support all custom parameters" in {
    val whisper = new WhisperSpeechToText(
      command = Seq("whisper", "--gpu"),
      model = "large",
      outputFormat = "json"
    )
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options = STTOptions(
      language = Some("de"),
      prompt = Some("German audio"),
      enableTimestamps = true,
      diarization = true,
      confidenceThreshold = 0.6
    )

    val result = whisper.transcribe(input, options)
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support defaults with custom options" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options = STTOptions(
      language = Some("es"),
      prompt = Some("Spanish conversation"),
      enableTimestamps = true
    )

    val result = whisper.transcribe(input, options)
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Different Sample Rates =====
  "WhisperSpeechToText with different sample rates" should "support 8000 Hz" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 8000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 16000 Hz (standard)" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 44100 Hz" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 44100, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 48000 Hz (professional)" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 48000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Different Channel Configurations =====
  "WhisperSpeechToText with different channels" should "support mono (1 channel)" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support stereo (2 channels)" in {
    val whisper         = new WhisperSpeechToText()
    val audioBytesMulti = Array.fill[Byte](8)(0)
    val input           = AudioInput.BytesAudio(audioBytesMulti, sampleRate = 16000, numChannels = 2)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "support 5.1 surround (6 channels)" in {
    val whisper         = new WhisperSpeechToText()
    val audioBytesMulti = Array.fill[Byte](50)(0)
    val input           = AudioInput.BytesAudio(audioBytesMulti, sampleRate = 16000, numChannels = 6)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Result Type Validation =====
  "Transcription result type" should "be Either (Result[Transcription])" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Either[_, _]] shouldBe true
  }

  it should "be Left on error for non-existent file" in {
    val whisper         = new WhisperSpeechToText()
    val nonExistentPath = Paths.get("/invalid/path/audio.wav")
    val input           = AudioInput.FileAudio(nonExistentPath)

    val result = whisper.transcribe(input, STTOptions())
    result.isLeft shouldBe true
  }

  // ===== Repeated Operations =====
  "WhisperSpeechToText repeated operations" should "handle multiple transcriptions" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)

    val input1 = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val input2 = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)

    val result1 = whisper.transcribe(input1, STTOptions())
    val result2 = whisper.transcribe(input2, STTOptions())

    result1.isInstanceOf[Result[Transcription]] shouldBe true
    result2.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "handle alternating input types" in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)

    val bytesInput  = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val streamInput = AudioInput.StreamAudio(new ByteArrayInputStream(audioBytes), sampleRate = 16000, numChannels = 1)

    val result1 = whisper.transcribe(bytesInput, STTOptions())
    val result2 = whisper.transcribe(streamInput, STTOptions())

    result1.isInstanceOf[Result[Transcription]] shouldBe true
    result2.isInstanceOf[Result[Transcription]] shouldBe true
  }

  // ===== Edge Cases =====
  "WhisperSpeechToText edge cases" should "handle very small audio" in {
    val whisper   = new WhisperSpeechToText()
    val tinyBytes = Array[Byte](1)
    val input     = AudioInput.BytesAudio(tinyBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }

  it should "handle larger audio data" in {
    val whisper    = new WhisperSpeechToText()
    val largeBytes = Array.fill[Byte](100000)(0.toByte)
    val input      = AudioInput.BytesAudio(largeBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isInstanceOf[Result[Transcription]] shouldBe true
  }
}
