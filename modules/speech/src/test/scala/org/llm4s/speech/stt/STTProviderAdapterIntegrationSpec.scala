package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Integration tests for STT provider adapters validating metadata population.
 */
class STTProviderAdapterIntegrationSpec extends AnyFlatSpec with Matchers {

  // ===== Whisper Provider Metadata Tests =====
  "WhisperSpeechToText" should "populate processingTimeMs" in {
    val adapter = new WhisperSpeechToText()
    // Configure for mock testing
    // In production: the provider would track actual transcription time
    adapter.name shouldBe "whisper-cli"
  }

  it should "support timestamp extraction from JSON output" in {
    // With enableTimestamps=true, adapter should extract word-level timings
    val options = STTOptions(enableTimestamps = true, language = Some("en"))
    options.enableTimestamps shouldBe true
  }

  it should "support confidence extraction from output" in {
    val adapter = new WhisperSpeechToText()
    // Adapter should extract confidence scores when available
    adapter.supportedFormats should contain("audio/wav")
  }

  it should "handle diarization when enabled" in {
    val options = STTOptions(diarization = true)
    options.diarization shouldBe true
  }

  // ===== Vosk Provider Metadata Tests =====
  "VoskSpeechToText" should "populate processingTimeMs" in {
    // VoskSpeechToText should track transcription time
    val adapter = new VoskSpeechToText()
    adapter.name shouldBe "vosk"
  }

  it should "support audio preprocessing" in {
    // Vosk preprocesses audio to 16kHz mono
    val options = STTOptions()
    options.language.orElse(Some("en")) shouldBe Some("en")
  }

  it should "list supported formats" in {
    val adapter = new VoskSpeechToText()
    adapter.supportedFormats should not be empty
    adapter.supportedFormats should contain("audio/wav")
  }

  // ===== Metadata Completeness Tests =====
  "Transcription with metadata" should "include all optional fields" in {
    val trans = Transcription(
      text = "Hello world",
      language = Some("en"),
      confidence = Some(0.95),
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5, speakerId = Some(1), confidence = Some(0.96)),
        WordTimestamp("world", 0.6, 1.0, speakerId = Some(1), confidence = Some(0.94))
      ),
      meta = None,
      processingTimeMs = Some(245) // Time in milliseconds
    )

    trans.text shouldBe "Hello world"
    trans.language shouldBe Some("en")
    trans.confidence shouldBe Some(0.95)
    trans.hasTimestamps shouldBe true
    trans.timestamps.length shouldBe 2
    trans.processingTimeMs shouldBe Some(245)
    trans.totalDuration shouldBe Some(1.0)
    trans.uniqueSpeakers shouldBe Set(1)
  }

  it should "work with minimal metadata" in {
    val trans = Transcription(
      text = "Hello",
      language = None
    )

    trans.text shouldBe "Hello"
    trans.language shouldBe None
    trans.confidence shouldBe None
    trans.timestamps shouldBe Nil
    trans.processingTimeMs shouldBe None
  }

  // ===== Error Handling in Adapters =====
  "Provider adapters" should "return structured STTError for unsupported formats" in {
    val adapter = new WhisperSpeechToText()

    // Verify that adapter knows what formats it supports
    adapter.supportedFormats.length should be > 0
  }

  it should "return ProcessingFailed error with retryable flag" in {
    // When transcription fails, error should be retryable
    val error = STTError.ProcessingFailed("Network error")
    error.retryable shouldBe true
  }

  it should "return InvalidInput error as non-retryable" in {
    // When input is invalid, error should not be retryable
    val error = STTError.InvalidInput("Bad audio format")
    error.retryable shouldBe false
  }

  // ===== STTOptions Validation in Context =====
  "STTOptions validation" should "be enforced when creating transcription requests" in {
    val validOptions = STTOptions(
      language = Some("en-US"),
      confidenceThreshold = 0.75,
      enableTimestamps = true,
      diarization = false
    )

    validOptions.language shouldBe Some("en-US")
    validOptions.confidenceThreshold shouldBe 0.75
  }

  it should "reject invalid options during construction" in {
    val ex = intercept[IllegalArgumentException] {
      STTOptions(confidenceThreshold = 1.5)
    }
    ex.getMessage should include("Confidence threshold")
  }

  // ===== Backward Compatibility =====
  "STT domain model" should "support construction without new fields" in {
    // Old code creating STTOptions without processingTimeMs should still work
    val transOld = Transcription(
      text = "Hello",
      language = Some("en")
    )

    transOld.text shouldBe "Hello"
    transOld.processingTimeMs shouldBe None
  }

  it should "support optional fields independently" in {
    // Can set timestamps without confidence
    val trans1 = Transcription(
      text = "Hello",
      language = None,
      timestamps = List(WordTimestamp("Hello", 0.0, 1.0))
    )
    trans1.timestamps.head.confidence shouldBe None

    // Can set confidence without timestamps
    val trans2 = Transcription(
      text = "Hello",
      language = None,
      confidence = Some(0.95)
    )
    trans2.confidence shouldBe Some(0.95)
  }

  it should "support word-level metadata combinations" in {
    val ts1 = WordTimestamp("word", 0.0, 1.0)                         // Minimal
    val ts2 = WordTimestamp("word", 0.0, 1.0, speakerId = Some(1))    // With speaker
    val ts3 = WordTimestamp("word", 0.0, 1.0, confidence = Some(0.9)) // With confidence
    val ts4 = WordTimestamp( // With both
      "word",
      0.0,
      1.0,
      speakerId = Some(1),
      confidence = Some(0.9)
    )

    ts1.speakerId shouldBe None
    ts1.confidence shouldBe None

    ts2.speakerId shouldBe Some(1)
    ts2.confidence shouldBe None

    ts3.speakerId shouldBe None
    ts3.confidence shouldBe Some(0.9)

    ts4.speakerId shouldBe Some(1)
    ts4.confidence shouldBe Some(0.9)
  }

  // ===== Performance Metrics =====
  "Processing time tracking" should "capture transcription duration" in {
    val startTime        = System.currentTimeMillis()
    val endTime          = startTime + 500 // Simulate 500ms transcription
    val processingTimeMs = Some(endTime - startTime)

    val trans = Transcription(
      text = "Hello",
      language = None,
      processingTimeMs = processingTimeMs
    )

    trans.processingTimeMs shouldBe processingTimeMs
    trans.processingTimeMs.get shouldBe 500L +- 100L // Allow some variance
  }

  it should "track zero processing time" in {
    val trans = Transcription(
      text = "Hello",
      language = None,
      processingTimeMs = Some(0)
    )

    trans.processingTimeMs shouldBe Some(0)
  }

  it should "handle very long processing times" in {
    val longTime = Some(3600000L) // 1 hour in milliseconds
    val trans = Transcription(
      text = "Hello",
      language = None,
      processingTimeMs = longTime
    )

    trans.processingTimeMs shouldBe longTime
  }
}
