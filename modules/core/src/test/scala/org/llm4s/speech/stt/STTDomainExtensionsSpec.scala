package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for extended STT domain model features:
 * - Typed validation (Result-based) for STTOptions and WordTimestamp
 * - Enhanced helper methods for Transcription
 * - Edge cases and backward compatibility
 */
class STTDomainExtensionsSpec extends AnyFlatSpec with Matchers {

  // ===== STTOptions.validate Factory Tests =====
  "STTOptions.validate factory" should "create valid options for all defaults" in {
    val result = STTOptions.validate()
    result shouldBe a[Right[_, _]]
    result.foreach { opts =>
      opts.language shouldBe None
      opts.confidenceThreshold shouldBe 0.0
    }
  }

  it should "accept valid BCP 47 language tags" in {
    val validTags = List("en", "fr", "en-US", "en-GB", "zh-CN", "pt-BR")
    validTags.foreach { tag =>
      val result = STTOptions.validate(language = Some(tag))
      result shouldBe a[Right[_, _]]
    }
  }

  it should "reject invalid language tags" in {
    val invalidTags = List("english", "E", "en_US", "en-us", "en-USA")
    invalidTags.foreach { tag =>
      val result = STTOptions.validate(language = Some(tag))
      result shouldBe a[Left[_, _]]
      result.left.foreach(error => error shouldBe a[STTError.InvalidInput])
    }
  }

  it should "validate confidence threshold bounds" in {
    val result1 = STTOptions.validate(confidenceThreshold = 0.0)
    result1 shouldBe a[Right[_, _]]

    val result2 = STTOptions.validate(confidenceThreshold = 1.0)
    result2 shouldBe a[Right[_, _]]

    val result3 = STTOptions.validate(confidenceThreshold = 0.5)
    result3 shouldBe a[Right[_, _]]
  }

  it should "reject confidence threshold outside [0.0, 1.0]" in {
    val result1 = STTOptions.validate(confidenceThreshold = -0.1)
    result1 shouldBe a[Left[_, _]]

    val result2 = STTOptions.validate(confidenceThreshold = 1.1)
    result2 shouldBe a[Left[_, _]]

    val result3 = STTOptions.validate(confidenceThreshold = 2.0)
    result3 shouldBe a[Left[_, _]]
  }

  it should "validate prompt length" in {
    val shortPrompt = "Medical context"
    val result1     = STTOptions.validate(prompt = Some(shortPrompt))
    result1 shouldBe a[Right[_, _]]

    val longPrompt = "x" * 4000
    val result2    = STTOptions.validate(prompt = Some(longPrompt))
    result2 shouldBe a[Right[_, _]]
  }

  it should "reject overly long prompts" in {
    val tooLong = "x" * 4001
    val result  = STTOptions.validate(prompt = Some(tooLong))
    result shouldBe a[Left[_, _]]
  }

  it should "include context in validation error" in {
    val result = STTOptions.validate(
      language = Some("en-US"),
      confidenceThreshold = 1.5
    )
    result shouldBe a[Left[_, _]]
    result.left.foreach { error =>
      error.context.get("language") shouldBe Some("en-US")
      error.context.get("confidenceThreshold") shouldBe Some("1.5")
    }
  }

  it should "collect multiple validation errors" in {
    val result = STTOptions.validate(
      language = Some("invalid"),
      confidenceThreshold = -0.5,
      prompt = Some("x" * 4001)
    )
    result shouldBe a[Left[_, _]]
    result.left.foreach(error => error.message should include(";"))
  }

  // ===== WordTimestamp.validate Factory Tests =====
  "WordTimestamp.validate factory" should "create valid timestamps for basic case" in {
    val result = WordTimestamp.validate(
      word = "hello",
      startSec = 0.0,
      endSec = 0.5
    )
    result shouldBe a[Right[_, _]]
  }

  it should "accept valid time ranges" in {
    val validCases = List(
      (0.0, 0.5),
      (1.0, 1.0), // Zero duration is valid
      (10.5, 20.3),
      (100.0, 1000.0)
    )
    validCases.foreach { case (start, end) =>
      val result = WordTimestamp.validate(
        word = "test",
        startSec = start,
        endSec = end
      )
      result shouldBe a[Right[_, _]]
    }
  }

  it should "reject invalid time ranges" in {
    val result1 = WordTimestamp.validate(
      word = "test",
      startSec = 1.0,
      endSec = 0.5
    )
    result1 shouldBe a[Left[_, _]]

    val result2 = WordTimestamp.validate(
      word = "test",
      startSec = -0.1,
      endSec = 0.5
    )
    result2 shouldBe a[Left[_, _]]
  }

  it should "reject empty words" in {
    val result = WordTimestamp.validate(
      word = "",
      startSec = 0.0,
      endSec = 0.5
    )
    result shouldBe a[Left[_, _]]
  }

  it should "validate confidence bounds" in {
    val validCases = List(Some(0.0), Some(0.5), Some(1.0), None)
    validCases.foreach { confidence =>
      val result = WordTimestamp.validate(
        word = "test",
        startSec = 0.0,
        endSec = 0.5,
        confidence = confidence
      )
      result shouldBe a[Right[_, _]]
    }
  }

  it should "reject invalid confidence scores" in {
    val result1 = WordTimestamp.validate(
      word = "test",
      startSec = 0.0,
      endSec = 0.5,
      confidence = Some(-0.1)
    )
    result1 shouldBe a[Left[_, _]]

    val result2 = WordTimestamp.validate(
      word = "test",
      startSec = 0.0,
      endSec = 0.5,
      confidence = Some(1.1)
    )
    result2 shouldBe a[Left[_, _]]
  }

  it should "include context in validation errors" in {
    val result = WordTimestamp.validate(
      word = "test",
      startSec = 1.0,
      endSec = 0.5,
      confidence = Some(0.95)
    )
    result shouldBe a[Left[_, _]]
    result.left.foreach { error =>
      error.context.get("word") shouldBe Some("test")
      error.context.get("startSec") shouldBe Some("1.0")
    }
  }

  // ===== WordTimestamp Helper Methods Tests =====
  "WordTimestamp.meetsConfidence" should "pass when confidence not set" in {
    val ts = WordTimestamp("test", 0.0, 0.5, confidence = None)
    ts.meetsConfidence(0.5) shouldBe true
    ts.meetsConfidence(0.9) shouldBe true
  }

  it should "pass when confidence meets threshold" in {
    val ts = WordTimestamp("test", 0.0, 0.5, confidence = Some(0.95))
    ts.meetsConfidence(0.9) shouldBe true
    ts.meetsConfidence(0.95) shouldBe true
  }

  it should "fail when confidence below threshold" in {
    val ts = WordTimestamp("test", 0.0, 0.5, confidence = Some(0.8))
    ts.meetsConfidence(0.85) shouldBe false
    ts.meetsConfidence(0.9) shouldBe false
  }

  "WordTimestamp.withTimeAdjustment" should "apply positive offset" in {
    val ts       = WordTimestamp("test", 1.0, 1.5)
    val adjusted = ts.withTimeAdjustment(startOffset = 0.5, endOffset = 0.5)
    adjusted.startSec shouldBe 1.5
    adjusted.endSec shouldBe 2.0
  }

  it should "apply negative offset" in {
    val ts       = WordTimestamp("test", 1.0, 1.5)
    val adjusted = ts.withTimeAdjustment(startOffset = -0.2, endOffset = 0.0)
    adjusted.startSec shouldBe 0.8
    adjusted.endSec shouldBe 1.5
  }

  it should "clamp start time to 0" in {
    val ts       = WordTimestamp("test", 0.2, 0.7)
    val adjusted = ts.withTimeAdjustment(startOffset = -0.5)
    adjusted.startSec should be >= 0.0
  }

  // ===== Transcription Enhancement Tests =====
  "Transcription.wordCount" should "return timestamp count when available" in {
    val trans = Transcription(
      text = "a b c d e",
      language = None,
      timestamps = List(
        WordTimestamp("a", 0.0, 0.1),
        WordTimestamp("b", 0.2, 0.3),
        WordTimestamp("c", 0.4, 0.5)
      )
    )
    trans.wordCount shouldBe 3
  }

  it should "estimate from text when no timestamps" in {
    val trans = Transcription(
      text = "a b c d e",
      language = None,
      timestamps = Nil
    )
    trans.wordCount shouldBe 5
  }

  it should "handle single word" in {
    val trans = Transcription(text = "hello", language = None)
    trans.wordCount shouldBe 1
  }

  "Transcription.filterByConfidence" should "exclude words without confidence scores" in {
    val trans = Transcription(
      text = "hello world",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5), // No confidence
        WordTimestamp("world", 0.6, 1.1, confidence = Some(0.8))
      )
    )
    val filtered = trans.filterByConfidence(0.5)
    // Only words with confidence >= 0.5 are kept
    filtered.timestamps.length shouldBe 1
    filtered.timestamps.head.word shouldBe "world"
  }

  it should "keep words that meet confidence threshold" in {
    val trans = Transcription(
      text = "hello world test",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5, confidence = Some(0.95)),
        WordTimestamp("world", 0.6, 1.1, confidence = Some(0.5)),
        WordTimestamp("test", 1.2, 1.7, confidence = Some(0.8))
      )
    )
    val filtered = trans.filterByConfidence(0.7)
    // Only hello and test pass the threshold
    filtered.timestamps.length shouldBe 2
  }

  "Transcription.averageConfidence" should "return None when no confidence scores" in {
    val trans = Transcription(
      text = "hello world",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5),
        WordTimestamp("world", 0.6, 1.1)
      )
    )
    trans.averageConfidence shouldBe None
  }

  it should "calculate average of existing confidence scores" in {
    val trans = Transcription(
      text = "hello world",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5, confidence = Some(0.9)),
        WordTimestamp("world", 0.6, 1.1, confidence = Some(0.8))
      )
    )
    val avg = trans.averageConfidence
    avg should not be empty
    avg.get should be(0.85 +- 0.02) // Slightly relaxed for FP precision
  }

  it should "handle mixed confidence (some have, some don't)" in {
    val trans = Transcription(
      text = "hello world test",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5, confidence = Some(0.9)),
        WordTimestamp("world", 0.6, 1.1, confidence = None),
        WordTimestamp("test", 1.2, 1.7, confidence = Some(0.8))
      )
    )
    val avg = trans.averageConfidence
    avg should not be empty
    avg.get should be((0.9 + 0.8) / 2 +- 0.02) // Slightly relaxed for FP precision
  }

  "Transcription.minConfidence" should "return None when no timestamps" in {
    val trans = Transcription(text = "hello", language = None)
    trans.minConfidence shouldBe None
  }

  it should "find minimum confidence" in {
    val trans = Transcription(
      text = "a b c",
      language = None,
      timestamps = List(
        WordTimestamp("a", 0.0, 0.1, confidence = Some(0.95)),
        WordTimestamp("b", 0.2, 0.3, confidence = Some(0.75)),
        WordTimestamp("c", 0.4, 0.5, confidence = Some(0.85))
      )
    )
    trans.minConfidence shouldBe Some(0.75)
  }

  "Transcription.maxConfidence" should "find maximum confidence" in {
    val trans = Transcription(
      text = "a b c",
      language = None,
      timestamps = List(
        WordTimestamp("a", 0.0, 0.1, confidence = Some(0.95)),
        WordTimestamp("b", 0.2, 0.3, confidence = Some(0.75)),
        WordTimestamp("c", 0.4, 0.5, confidence = Some(0.85))
      )
    )
    trans.maxConfidence shouldBe Some(0.95)
  }

  "Transcription.wordsBySpeaker" should "return empty list for non-existent speaker" in {
    val trans = Transcription(
      text = "hello world",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5, speakerId = Some(1)),
        WordTimestamp("world", 0.6, 1.1, speakerId = Some(1))
      )
    )
    trans.wordsBySpeaker(2) shouldBe Nil
  }

  it should "return words in chronological order for speaker" in {
    val trans = Transcription(
      text = "A says hello, B says hi, A says goodbye",
      language = None,
      timestamps = List(
        WordTimestamp("hello", 0.0, 0.5, speakerId = Some(1)),
        WordTimestamp("hi", 0.6, 1.0, speakerId = Some(2)),
        WordTimestamp("goodbye", 1.1, 1.6, speakerId = Some(1))
      )
    )
    trans.wordsBySpeaker(1) shouldBe List("hello", "goodbye")
    trans.wordsBySpeaker(2) shouldBe List("hi")
  }

  "Transcription.speakerSegments" should "map segments by speaker" in {
    val trans = Transcription(
      text = "Speaker 1 talks, then Speaker 2",
      language = None,
      timestamps = List(
        WordTimestamp("word1", 0.0, 0.5, speakerId = Some(1)),
        WordTimestamp("word2", 0.6, 1.0, speakerId = Some(1)),
        WordTimestamp("word3", 1.1, 1.6, speakerId = Some(2))
      )
    )
    val segments = trans.speakerSegments
    segments.keys should contain(1)
    segments.keys should contain(2)
    segments(1).length shouldBe 2
    segments(2).length shouldBe 1
  }

  it should "return empty map when no speaker IDs" in {
    val trans = Transcription(
      text = "no speakers",
      language = None,
      timestamps = List(
        WordTimestamp("no", 0.0, 0.5),
        WordTimestamp("speakers", 0.6, 1.1)
      )
    )
    trans.speakerSegments shouldBe Map.empty
  }

  "Transcription.meetsQualityThreshold" should "pass safe defaults" in {
    val trans = Transcription(
      text = "hello",
      language = None,
      confidence = Some(0.8)
    )
    trans.meetsQualityThreshold() shouldBe true
  }

  it should "use overall confidence when available" in {
    val trans = Transcription(
      text = "hello world",
      language = None,
      confidence = Some(0.6)
    )
    trans.meetsQualityThreshold(minConfidence = 0.5) shouldBe true
    trans.meetsQualityThreshold(minConfidence = 0.7) shouldBe false
  }

  it should "use average confidence from timestamps when overall unavailable" in {
    val trans = Transcription(
      text = "a b",
      language = None,
      confidence = None,
      timestamps = List(
        WordTimestamp("a", 0.0, 0.1, confidence = Some(0.6)),
        WordTimestamp("b", 0.2, 0.3, confidence = Some(0.6))
      )
    )
    trans.meetsQualityThreshold(minConfidence = 0.5) shouldBe true
    trans.meetsQualityThreshold(minConfidence = 0.7) shouldBe false
  }

  it should "check word count threshold" in {
    val trans1 = Transcription(
      text = "single",
      language = None
    )
    trans1.meetsQualityThreshold(minWords = 1) shouldBe true
    trans1.meetsQualityThreshold(minWords = 2) shouldBe false

    val trans2 = Transcription(
      text = "hello world test",
      language = None
    )
    trans2.meetsQualityThreshold(minWords = 3) shouldBe true
    trans2.meetsQualityThreshold(minWords = 4) shouldBe false
  }

  // ===== Backward Compatibility Tests =====
  "Direct case class construction" should "still work for STTOptions" in {
    val opts = STTOptions(
      language = Some("en"),
      confidenceThreshold = 0.5
    )
    opts.language shouldBe Some("en")
    opts.confidenceThreshold shouldBe 0.5
  }

  it should "still work for WordTimestamp" in {
    val ts = WordTimestamp(
      word = "hello",
      startSec = 0.0,
      endSec = 0.5,
      speakerId = Some(1)
    )
    ts.word shouldBe "hello"
    ts.speakerId shouldBe Some(1)
  }

  it should "still work for Transcription" in {
    val trans = Transcription(
      text = "hello",
      language = Some("en"),
      processingTimeMs = Some(150)
    )
    trans.text shouldBe "hello"
    trans.processingTimeMs shouldBe Some(150)
  }

  // ===== Edge Cases =====
  "Special characters in text" should "be handled in validation" in {
    val result1 = WordTimestamp.validate(
      word = "café",
      startSec = 0.0,
      endSec = 0.5
    )
    result1 shouldBe a[Right[_, _]]

    val result2 = WordTimestamp.validate(
      word = "😀",
      startSec = 0.0,
      endSec = 0.5
    )
    result2 shouldBe a[Right[_, _]]
  }

  "Very large numbers" should "be handled in timestamps" in {
    val result = WordTimestamp.validate(
      word = "test",
      startSec = 3600.0,
      endSec = 3600.5
    )
    result shouldBe a[Right[_, _]]
  }

  "Floating point precision" should "be handled in confidence" in {
    val result = STTOptions.validate(confidenceThreshold = 0.1 + 0.2) // Notorious FP issue
    result shouldBe a[Right[_, _]]
  }
}
