package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for validation logic and edge cases in STT domain models.
 */
class STTValidationSpec extends AnyFlatSpec with Matchers {

  // ===== STTOptions Validation =====
  "STTOptions with confidence threshold" should "accept valid values" in {
    val opts0 = STTOptions(confidenceThreshold = 0.0)
    opts0.confidenceThreshold shouldBe 0.0

    val opts50 = STTOptions(confidenceThreshold = 0.5)
    opts50.confidenceThreshold shouldBe 0.5

    val opts100 = STTOptions(confidenceThreshold = 1.0)
    opts100.confidenceThreshold shouldBe 1.0
  }

  it should "reject negative confidence threshold" in {
    val ex = intercept[IllegalArgumentException] {
      STTOptions(confidenceThreshold = -0.1)
    }
    ex.getMessage should include("Confidence threshold must be between 0.0 and 1.0")
  }

  it should "reject confidence threshold above 1.0" in {
    val ex = intercept[IllegalArgumentException] {
      STTOptions(confidenceThreshold = 1.1)
    }
    ex.getMessage should include("Confidence threshold must be between 0.0 and 1.0")
  }

  it should "reject very large confidence thresholds" in {
    val ex = intercept[IllegalArgumentException] {
      STTOptions(confidenceThreshold = 999.0)
    }
    ex.getMessage should include("Confidence threshold must be between 0.0 and 1.0")
  }

  // ===== BCP 47 Language Tag Validation =====
  "STTOptions with language" should "accept valid BCP 47 tags" in {
    val validTags = List(
      "en",    // Two-letter code
      "en-US", // Language-Region
      "zh-CN", // Chinese (Simplified)
      "pt-BR", // Portuguese (Brazil)
      "fr",    // French
      "de-DE"  // German (Germany)
    )

    validTags.foreach { tag =>
      val opts = STTOptions(language = Some(tag))
      opts.language shouldBe Some(tag)
    }
  }

  it should "reject invalid language tags" in {
    val invalidTags = List(
      "invalid", // Too long
      "en-us",   // Wrong case (lowercase region)
      "123",     // Numbers
      "en-",     // Incomplete
      "-US",     // Missing language
      "en_US",   // Underscore instead of dash
      "eng",     // Three letters
      "english"  // Full word
    )

    invalidTags.foreach { tag =>
      val result = STTOptions.validate(language = Some(tag))
      result shouldBe a[Left[_, _]]
      result match {
        case Left(error) => error.message should include("Language tag")
        case Right(_)    => fail("Expected Left but got Right")
      }
    }
  }

  it should "accept None for language (optional)" in {
    val opts = STTOptions(language = None)
    opts.language shouldBe None
  }

  // ===== WordTimestamp Validation =====
  "WordTimestamp with timestamps" should "accept valid time ranges" in {
    val ts1 = WordTimestamp("hello", 0.0, 0.5)
    ts1.startSec shouldBe 0.0
    ts1.endSec shouldBe 0.5
    ts1.duration shouldBe 0.5

    val ts2 = WordTimestamp("world", 1.0, 2.0)
    ts2.startSec shouldBe 1.0
    ts2.endSec shouldBe 2.0
    ts2.duration shouldBe 1.0
  }

  it should "reject when endSec < startSec" in {
    val ex = intercept[IllegalArgumentException] {
      WordTimestamp("word", 2.0, 1.0)
    }
    ex.getMessage should include("Invalid timestamp: end must be >= start and >= 0")
  }

  it should "reject negative start time" in {
    val ex = intercept[IllegalArgumentException] {
      WordTimestamp("word", -0.5, 1.0)
    }
    ex.getMessage should include("Invalid timestamp: end must be >= start and >= 0")
  }

  it should "accept zero-duration words" in {
    val ts = WordTimestamp("word", 1.0, 1.0)
    ts.duration shouldBe 0.0
  }

  it should "accept very small durations" in {
    val ts = WordTimestamp("word", 0.0, 0.001)
    ts.duration shouldBe 0.001 +- 0.0001
  }

  it should "accept very large time values" in {
    val ts = WordTimestamp("word", 1000.0, 3600.0)
    ts.duration shouldBe 2600.0 +- 0.1
  }

  // ===== Transcription Helper Methods =====
  "Transcription.filterByConfidence" should "filter low confidence words" in {
    val trans = Transcription(
      text = "Hello world",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5, confidence = Some(0.95)),
        WordTimestamp("world", 0.6, 1.0, confidence = Some(0.5))
      )
    )

    val filtered = trans.filterByConfidence(0.7)
    filtered.timestamps.length shouldBe 1
    filtered.timestamps.head.word shouldBe "Hello"
  }

  it should "keep words at exactly threshold confidence" in {
    val trans = Transcription(
      text = "Hello",
      language = None,
      timestamps = List(WordTimestamp("Hello", 0.0, 0.5, confidence = Some(0.7)))
    )

    val filtered = trans.filterByConfidence(0.7)
    filtered.timestamps.length shouldBe 1
  }

  it should "handle threshold at boundaries" in {
    val trans = Transcription(
      text = "Words",
      language = None,
      timestamps = List(
        WordTimestamp("a", 0.0, 0.2, confidence = Some(0.0)),
        WordTimestamp("b", 0.2, 0.4, confidence = Some(0.5)),
        WordTimestamp("c", 0.4, 0.6, confidence = Some(1.0))
      )
    )

    val filtered0 = trans.filterByConfidence(0.0)
    filtered0.timestamps.length shouldBe 3

    val filtered100 = trans.filterByConfidence(1.0)
    filtered100.timestamps.length shouldBe 1
    filtered100.timestamps.head.confidence shouldBe Some(1.0)
  }

  it should "exclude words without confidence scores" in {
    val trans = Transcription(
      text = "Hello world",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5), // No confidence
        WordTimestamp("world", 0.6, 1.0, confidence = Some(0.8))
      )
    )

    val filtered = trans.filterByConfidence(0.5)
    filtered.timestamps.length shouldBe 1
    filtered.timestamps.head.word shouldBe "world"
  }

  it should "return empty list when threshold too high" in {
    val trans = Transcription(
      text = "Hello",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5, confidence = Some(0.5))
      )
    )

    val filtered = trans.filterByConfidence(0.99)
    filtered.timestamps shouldBe Nil
  }

  // ===== Transcription Duration Calculations =====
  "Transcription.totalDuration" should "calculate correctly with multiple timestamps" in {
    val trans = Transcription(
      text = "Hello world",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5),
        WordTimestamp("world", 0.6, 1.5)
      )
    )

    trans.totalDuration shouldBe Some(1.5)
  }

  it should "handle unsorted timestamps (should use last endSec)" in {
    val trans = Transcription(
      text = "Unsorted",
      language = None,
      timestamps = List(
        WordTimestamp("end", 2.0, 3.0),  // Last in list but semantically last
        WordTimestamp("start", 0.0, 1.0) // First semantically but second in list
      )
    )

    trans.totalDuration shouldBe Some(3.0)
  }

  it should "return None for empty timestamps" in {
    val trans = Transcription(text = "Hello", language = None, timestamps = Nil)
    trans.totalDuration shouldBe None
  }

  it should "handle single timestamp" in {
    val trans = Transcription(
      text = "Single",
      language = None,
      timestamps = List(WordTimestamp("word", 0.0, 1.0))
    )

    trans.totalDuration shouldBe Some(1.0)
  }

  it should "handle very long durations" in {
    val trans = Transcription(
      text = "Long",
      language = None,
      timestamps = List(WordTimestamp("word", 0.0, 3600.0))
    )

    trans.totalDuration shouldBe Some(3600.0)
  }

  // ===== Speaker Identification =====
  "Transcription.uniqueSpeakers" should "identify distinct speakers" in {
    val trans = Transcription(
      text = "Speaker conversation",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5, speakerId = Some(1)),
        WordTimestamp("Hi", 0.6, 1.0, speakerId = Some(2)),
        WordTimestamp("How", 1.1, 1.5, speakerId = Some(1))
      )
    )

    trans.uniqueSpeakers shouldBe Set(1, 2)
  }

  it should "return empty set when no speaker IDs" in {
    val trans = Transcription(
      text = "No speakers",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5),
        WordTimestamp("world", 0.6, 1.0)
      )
    )

    trans.uniqueSpeakers shouldBe Set()
  }

  it should "handle single speaker only" in {
    val trans = Transcription(
      text = "Single speaker",
      language = None,
      timestamps = List(
        WordTimestamp("Hello", 0.0, 0.5, speakerId = Some(1)),
        WordTimestamp("world", 0.6, 1.0, speakerId = Some(1))
      )
    )

    trans.uniqueSpeakers shouldBe Set(1)
  }

  it should "handle mixed speaker IDs and no IDs" in {
    val trans = Transcription(
      text = "Mixed",
      language = None,
      timestamps = List(
        WordTimestamp("A", 0.0, 0.5, speakerId = Some(1)),
        WordTimestamp("B", 0.5, 1.0), // No speaker ID
        WordTimestamp("C", 1.0, 1.5, speakerId = Some(2))
      )
    )

    trans.uniqueSpeakers shouldBe Set(1, 2)
  }

  it should "handle many speakers" in {
    val timestamps = (1 to 10).map(i => WordTimestamp(s"word$i", (i - 1) * 0.5, i * 0.5, speakerId = Some(i))).toList

    val trans = Transcription(
      text = "Many speakers",
      language = None,
      timestamps = timestamps
    )

    trans.uniqueSpeakers.size shouldBe 10
    trans.uniqueSpeakers shouldBe (1 to 10).toSet
  }

  // ===== Edge Cases =====
  "STTOptions" should "preserve all field values when copied" in {
    val original = STTOptions(
      language = Some("en-US"),
      prompt = Some("Medical terms"),
      enableTimestamps = true,
      diarization = true,
      confidenceThreshold = 0.75
    )

    val copy = original.copy(confidenceThreshold = 0.9)
    copy.language shouldBe Some("en-US")
    copy.prompt shouldBe Some("Medical terms")
    copy.enableTimestamps shouldBe true
    copy.diarization shouldBe true
    copy.confidenceThreshold shouldBe 0.9
  }

  "Transcription" should "handle very long text" in {
    val longText = "word " * 10000
    val trans    = Transcription(text = longText, language = Some("en"))
    trans.text.length should be > 10000
  }

  it should "handle unicode in transcription text" in {
    val trans = Transcription(
      text = "Hello 世界 Привет مرحبا",
      language = Some("mul") // Multilingual
    )
    trans.text should include("世界")
  }
}
