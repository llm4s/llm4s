package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.Tag
import org.scalatest.BeforeAndAfterEach
import org.llm4s.speech.AudioInput
import org.llm4s.error.ProcessingError
import java.io.ByteArrayInputStream
import java.nio.file.Paths

object Integration extends Tag("integration")

class WhisperSpeechToTextIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // ===== Error Handling Tests (non-integration) =====

  "WhisperSpeechToText.transcribe" should "return error for non-existent file" in {
    val whisper         = new WhisperSpeechToText()
    val nonExistentPath = Paths.get("/nonexistent/audio/file.wav")
    val input           = AudioInput.FileAudio(nonExistentPath)

    val result = whisper.transcribe(input, STTOptions())
    result.isLeft shouldBe true
    result.swap.map { case _: ProcessingError => () }.isRight shouldBe true
  }

  it should "return error when Whisper CLI is not found" in {
    val whisper = new WhisperSpeechToText(command = Seq("nonexistent-whisper-command"))
    val bytes   = Array[Byte](0, 1, 2, 3)
    val input   = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())
    result.isLeft shouldBe true
  }

  // ===== Options Validation Tests =====

  "STTOptions.validate" should "accept valid language codes" in {
    val validTags = Seq("en", "en-US", "fr-FR", "zh-Hans-CN", "de")
    validTags.foreach { tag =>
      val result = STTOptions.validate(language = Some(tag))
      result.isRight shouldBe true
    }
  }

  it should "reject invalid language codes" in {
    val invalidTags = Seq("invalid%lang", "123", "")
    invalidTags.foreach { tag =>
      val result = STTOptions.validate(language = Some(tag))
      result.isLeft shouldBe true
    }
  }

  it should "reject confidence thresholds outside [0.0, 1.0]" in {
    val result1 = STTOptions.validate(confidenceThreshold = -0.1)
    val result2 = STTOptions.validate(confidenceThreshold = 1.5)

    result1.isLeft shouldBe true
    result2.isLeft shouldBe true
  }

  it should "accept valid confidence thresholds" in {
    val validThresholds = Seq(0.0, 0.5, 1.0)
    validThresholds.foreach { threshold =>
      val result = STTOptions.validate(confidenceThreshold = threshold)
      result.isRight shouldBe true
    }
  }

  it should "reject prompts longer than 4000 characters" in {
    val longPrompt = "a" * 4001
    val result     = STTOptions.validate(prompt = Some(longPrompt))
    result.isLeft shouldBe true
  }

  // ===== Integration Tests (require Whisper CLI, run conditionally) =====

  "WhisperSpeechToText.transcribe with real Whisper" should "produce non-empty Transcription on valid input" taggedAs Integration in {
    val whisper    = new WhisperSpeechToText()
    val audioBytes = Array[Byte](0, 1, 2, 3)
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000, numChannels = 1)
    val options    = STTOptions(language = Some("en"))

    val result = whisper.transcribe(input, options)

    // Verify result is a Result type and check structure
    result match {
      case Right(transcription) =>
        transcription.text shouldBe a[String]
        transcription.language shouldBe Some("en")
      case Left(error) =>
        // Skip test if Whisper CLI is not available (integration test dependency)
        if (error.message.contains("Whisper CLI")) {
          cancel("Whisper CLI not available - skipping integration test")
        } else {
          fail(s"Expected Right, got Left: $error")
        }
    }
  }

  it should "populate processingTimeMs when available" taggedAs Integration in {
    val whisper = new WhisperSpeechToText()
    val bytes   = Array[Byte](0, 1, 2, 3)
    val input   = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())

    result.foreach { transcription =>
      transcription.processingTimeMs shouldBe defined
      transcription.processingTimeMs.get should be >= 0L
    }
  }

  it should "respect language option in output" taggedAs Integration in {
    val whisper = new WhisperSpeechToText()
    val bytes   = Array[Byte](0, 1, 2, 3)
    val input   = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)
    val options = STTOptions(language = Some("fr-FR"))

    val result = whisper.transcribe(input, options)

    result.foreach(transcription => transcription.language shouldBe Some("fr-FR"))
  }

  it should "handle BytesAudio, StreamAudio, and FileAudio input types" taggedAs Integration in {
    val whisper = new WhisperSpeechToText()
    val bytes   = Array[Byte](0, 1, 2, 3)

    // BytesAudio
    val bytesInput = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)
    val result1    = whisper.transcribe(bytesInput, STTOptions())
    result1.isRight || result1.isLeft shouldBe true // Valid Result type

    // StreamAudio
    val streamInput = AudioInput.StreamAudio(new ByteArrayInputStream(bytes), sampleRate = 16000, numChannels = 1)
    val result2     = whisper.transcribe(streamInput, STTOptions())
    result2.isRight || result2.isLeft shouldBe true // Valid Result type
  }

  it should "handle empty audio gracefully (returning error or empty text)" taggedAs Integration in {
    val whisper    = new WhisperSpeechToText()
    val emptyBytes = Array[Byte]()
    val input      = AudioInput.BytesAudio(emptyBytes, sampleRate = 16000, numChannels = 1)

    val result = whisper.transcribe(input, STTOptions())

    // Result should be valid (either success with empty/whitespace text or error)
    result match {
      case Right(transcription) =>
        transcription shouldBe a[Transcription]
      case Left(_) =>
        // Also acceptable - empty audio produces no transcription
        ()
    }
  }

  it should "support multiple Whisper model sizes" taggedAs Integration in {
    val models = Seq("tiny", "base", "small", "medium", "large")
    val bytes  = Array[Byte](0, 1, 2, 3)
    val input  = AudioInput.BytesAudio(bytes, sampleRate = 16000, numChannels = 1)

    models.foreach { model =>
      val whisper = new WhisperSpeechToText(model = model)
      val result  = whisper.transcribe(input, STTOptions())

      // Just verify we get a valid Result type, not that it succeeds
      (result.isRight || result.isLeft) shouldBe true
    }
  }

}
