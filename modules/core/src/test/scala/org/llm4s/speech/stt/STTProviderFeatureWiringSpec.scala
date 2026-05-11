package org.llm4s.speech.stt

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class STTProviderFeatureWiringSpec extends AnyFlatSpec with Matchers {

  "VoskSpeechToText.parseSegment" should "extract text and word timestamps from JSON" in {
    val json =
      """{
        |  "text": "hello world",
        |  "result": [
        |    { "conf": 0.92, "start": 0.0, "end": 0.4, "word": "hello" },
        |    { "conf": 0.61, "start": 0.5, "end": 0.9, "word": "world" }
        |  ]
        |}""".stripMargin

    val parsed = VoskSpeechToText.parseSegment(json)

    parsed.text shouldBe "hello world"
    parsed.words.map(_.word) shouldBe List("hello", "world")
    parsed.words.flatMap(_.confidence) shouldBe List(0.92, 0.61)
  }

  it should "filter timestamped words by confidence threshold" in {
    val words = List(
      WordTimestamp("hello", 0.0, 0.4, confidence = Some(0.92)),
      WordTimestamp("world", 0.5, 0.9, confidence = Some(0.41)),
      WordTimestamp("fallback", 1.0, 1.4, confidence = None)
    )

    val filtered = VoskSpeechToText.applyConfidenceThreshold(words, threshold = 0.5)

    filtered.map(_.word) shouldBe List("hello", "fallback")
  }

  "WhisperSpeechToText.parseOutput" should "extract timestamps and confidence from JSON output" in {
    val json =
      """{
        |  "text": "hello world",
        |  "language": "en",
        |  "segments": [
        |    {
        |      "words": [
        |        { "word": "hello", "start": 0.0, "end": 0.3, "probability": 0.9 },
        |        { "word": "world", "start": 0.4, "end": 0.8, "probability": 0.7 }
        |      ]
        |    }
        |  ]
        |}""".stripMargin

    val parsed = WhisperSpeechToText.parseOutput(json, STTOptions(enableTimestamps = true))

    parsed.text shouldBe "hello world"
    parsed.language shouldBe Some("en")
    parsed.timestamps.map(_.word) shouldBe List("hello", "world")
    parsed.confidence shouldBe defined
    parsed.confidence.get shouldBe 0.8 +- 0.0001
  }

  it should "apply the confidence threshold to parsed timestamp words" in {
    val json =
      """{
        |  "text": "keep drop",
        |  "words": [
        |    { "word": "keep", "start": 0.0, "end": 0.2, "confidence": 0.91 },
        |    { "word": "drop", "start": 0.3, "end": 0.5, "confidence": 0.2 }
        |  ]
        |}""".stripMargin

    val parsed = WhisperSpeechToText.parseOutput(
      json,
      STTOptions(enableTimestamps = true, confidenceThreshold = 0.5)
    )

    parsed.text shouldBe "keep"
    parsed.timestamps.map(_.word) shouldBe List("keep")
    parsed.confidence shouldBe Some(0.91)
  }

  it should "force JSON output when timestamps are requested" in {
    WhisperSpeechToText.effectiveOutputFormat("txt", STTOptions(enableTimestamps = true)) shouldBe "json"
    WhisperSpeechToText.effectiveOutputFormat("txt", STTOptions()) shouldBe "txt"
  }

  it should "prefer generated CLI output files over stdout when present" in {
    val inputPath  = Files.createTempFile("whisper-output", ".wav")
    val outputPath = inputPath.resolveSibling(inputPath.getFileName.toString + ".json")
    Files.writeString(outputPath, """{ "text": "from file" }""")

    try WhisperSpeechToText.resolveCliOutput(inputPath, "json", "from stdout") shouldBe """{ "text": "from file" }"""
    finally {
      Files.deleteIfExists(outputPath)
      Files.deleteIfExists(inputPath)
    }
  }

  "STTOptions.validateBatch" should "re-run strict validation for every element" in {
    val result = STTOptions.validateBatch(
      Seq(
        STTOptions(language = Some("en-US")),
        STTOptions(language = Some("english"))
      )
    )

    result.isLeft shouldBe true
    result.left.toOption.map(_.message) shouldBe Some("options[1]: Language tag 'english' is not a valid BCP 47 tag")
  }
}
