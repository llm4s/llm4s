package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Comprehensive tests for STT error types and their behavior.
 */
class STTErrorSpec extends AnyFlatSpec with Matchers {

  // ===== EngineNotAvailable Tests =====
  "STTError.EngineNotAvailable" should "be retryable" in {
    val error = STTError.EngineNotAvailable("Engine crashed")
    error.retryable shouldBe true
  }

  it should "have a user-friendly message" in {
    val error = STTError.EngineNotAvailable("Engine crashed")
    error.userFriendly should include("temporarily unavailable")
  }

  it should "contain the original message" in {
    val error = STTError.EngineNotAvailable("Engine not found at /usr/bin/vosk")
    error.message shouldBe "Engine not found at /usr/bin/vosk"
  }

  it should "support context information" in {
    val context = Map("version" -> "0.12.0", "model" -> "small")
    val error   = STTError.EngineNotAvailable("Model load failed", context = context)
    error.context shouldBe context
    error.context.get("version") shouldBe Some("0.12.0")
    error.context.get("model") shouldBe Some("small")
  }

  it should "have empty context by default" in {
    val error = STTError.EngineNotAvailable("Engine crashed")
    error.context shouldBe Map.empty
  }

  // ===== UnsupportedFormat Tests =====
  "STTError.UnsupportedFormat" should "not be retryable by default" in {
    val error = STTError.UnsupportedFormat("Format not supported", "audio/xyz", List())
    error.retryable shouldBe false
  }

  it should "contain format information" in {
    val error = STTError.UnsupportedFormat(
      "Unsupported format",
      format = "audio/wma",
      supported = List("audio/wav", "audio/mp3", "audio/flac")
    )
    error.format shouldBe "audio/wma"
    error.supported shouldBe List("audio/wav", "audio/mp3", "audio/flac")
  }

  it should "list all supported formats in user message" in {
    val error = STTError.UnsupportedFormat(
      "Format not supported",
      format = "audio/wma",
      supported = List("audio/wav", "audio/mp3")
    )
    error.userFriendly should include("audio/wma")
    error.userFriendly should include("audio/wav")
    error.userFriendly should include("audio/mp3")
  }

  it should "work with empty supported formats list" in {
    val error = STTError.UnsupportedFormat("Format not supported", "audio/xyz", Nil)
    error.supported shouldBe Nil
    error.userFriendly should not be empty
  }

  it should "work with single supported format" in {
    val error = STTError.UnsupportedFormat(
      "Format not supported",
      format = "audio/ogg",
      supported = List("audio/wav")
    )
    error.userFriendly should include("audio/wav")
  }

  // ===== ProcessingFailed Tests =====
  "STTError.ProcessingFailed" should "be retryable by default" in {
    val error = STTError.ProcessingFailed("Network timeout")
    error.retryable shouldBe true
  }

  it should "contain optional cause information" in {
    val cause = new RuntimeException("Connection refused")
    val error = STTError.ProcessingFailed("Transcription failed", cause = Some(cause))
    error.cause shouldBe Some(cause)
    error.cause.get.getMessage shouldBe "Connection refused"
  }

  it should "have None for cause when not provided" in {
    val error = STTError.ProcessingFailed("Transcription failed")
    error.cause shouldBe None
  }

  it should "provide user-friendly message" in {
    val error = STTError.ProcessingFailed("Internal server error")
    error.userFriendly should include("failed")
    error.userFriendly should include("audio")
  }

  it should "support context information" in {
    val context = Map("timeout_ms" -> "30000", "retry_count" -> "3")
    val error   = STTError.ProcessingFailed("Network timeout", context = context)
    error.context shouldBe context
  }

  // ===== InvalidInput Tests =====
  "STTError.InvalidInput" should "not be retryable" in {
    val error = STTError.InvalidInput("Confidence threshold invalid")
    error.retryable shouldBe false
  }

  it should "have a user-friendly message" in {
    val error = STTError.InvalidInput("Bad parameters")
    error.userFriendly should include("Invalid")
    error.userFriendly should include("audio")
  }

  it should "contain the original message" in {
    val error = STTError.InvalidInput("Confidence must be between 0.0 and 1.0")
    error.message shouldBe "Confidence must be between 0.0 and 1.0"
  }

  it should "support context for validation errors" in {
    val context = Map("field" -> "confidenceThreshold", "value" -> "1.5", "constraint" -> "[0.0, 1.0]")
    val error   = STTError.InvalidInput("Invalid confidence threshold", context = context)
    error.context shouldBe context
  }

  // ===== Error Polymorphism Tests =====
  "STTError variants" should "all extend STTError trait" in {
    val errors: List[STTError] = List(
      STTError.EngineNotAvailable("Engine down"),
      STTError.UnsupportedFormat("Bad format", "audio/xyz", List()),
      STTError.ProcessingFailed("Failed"),
      STTError.InvalidInput("Bad input")
    )
    errors.size shouldBe 4
    errors.foreach(e => e shouldBe a[STTError])
  }

  it should "have different retryability" in {
    val retryable    = STTError.EngineNotAvailable("Down")
    val nonRetryable = STTError.InvalidInput("Invalid")

    retryable.retryable shouldBe true
    nonRetryable.retryable shouldBe false
  }

  it should "provide meaningful user messages for all variants" in {
    val errors = List(
      STTError.EngineNotAvailable("Engine down"),
      STTError.UnsupportedFormat("Bad format", "audio/xyz", List("audio/wav")),
      STTError.ProcessingFailed("Network issue"),
      STTError.InvalidInput("Bad config")
    )

    errors.foreach { error =>
      error.userFriendly should not be empty
      error.userFriendly.length shouldBe >(5) // Reasonable length
    }
  }

  // ===== Error Context Handling =====
  "STTError context" should "be preserved through error propagation" in {
    val originalContext = Map(
      "timestamp"   -> "2025-04-06T10:30:00Z",
      "audio_file"  -> "test.wav",
      "duration_ms" -> "5000"
    )
    val error = STTError.ProcessingFailed("Processing timeout", context = originalContext)
    error.context shouldBe originalContext
  }

  it should "be mergeable in error scenarios" in {
    val baseError = STTError.ProcessingFailed("Network timeout", context = Map("attempt" -> "1"))
    val enhancedError = baseError.copy(
      context = baseError.context ++ Map("retry_at" -> "2025-04-06T10:35:00Z")
    )
    enhancedError.context("attempt") shouldBe "1"
    enhancedError.context("retry_at") shouldBe "2025-04-06T10:35:00Z"
  }

  // ===== Edge Cases =====
  "STTError.UnsupportedFormat" should "handle many supported formats" in {
    val manyFormats = (1 to 10).map(i => s"audio/format$i").toList
    val error       = STTError.UnsupportedFormat("Format not supported", "audio/xyz", manyFormats)
    error.supported.length shouldBe 10
    error.userFriendly should include("audio/format1")
    error.userFriendly should include("audio/format9")
    error.userFriendly should include("audio/format10")
  }

  it should "handle special characters in format names" in {
    val error = STTError.UnsupportedFormat(
      "Format not supported",
      "audio/x-custom+7.1",
      List("audio/wav", "audio/x-custom+2.0")
    )
    error.userFriendly should include("audio/x-custom+7.1")
    error.userFriendly should include("audio/x-custom+2.0")
  }

  "STTError.ProcessingFailed" should "handle null cause gracefully" in {
    val error = STTError.ProcessingFailed("Failed", cause = None)
    error.cause shouldBe None
    error.userFriendly should not be empty
  }

  it should "handle exception with null message" in {
    val cause = new RuntimeException()
    val error = STTError.ProcessingFailed("Failed", cause = Some(cause))
    error.cause.get shouldBe cause
    error.userFriendly should include("failed")
  }

  "STTError.InvalidInput" should "preserve empty context" in {
    val error = STTError.InvalidInput("Bad input", context = Map.empty)
    error.context shouldBe Map.empty
  }
}
