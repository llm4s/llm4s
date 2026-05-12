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

  it should "gracefully handle invalid json and missing confidence data" in {
    val parsed = VoskSpeechToText.parseSegment("not-json")

    parsed.text shouldBe ""
    parsed.words shouldBe Nil
    VoskSpeechToText.averageConfidence(parsed.words) shouldBe None
    VoskSpeechToText.renderWords(parsed.words) shouldBe ""
  }

  it should "ignore invalid word entries while keeping valid ones" in {
    val json =
      """{
        |  "text": "hello world",
        |  "result": [
        |    { "conf": 0.7, "start": 0.0, "end": 0.4, "word": "hello" },
        |    { "conf": 1.4, "start": 0.5, "end": 0.9, "word": "world" },
        |    { "start": 1.0, "end": 1.4, "word": "" }
        |  ]
        |}""".stripMargin

    val parsed = VoskSpeechToText.parseSegment(json)

    parsed.words.map(_.word) shouldBe List("hello")
    VoskSpeechToText.applyConfidenceThreshold(parsed.words, threshold = 0.0).map(_.word) shouldBe List("hello")
    VoskSpeechToText.averageConfidence(parsed.words) shouldBe Some(0.7)
  }

  it should "build transcription with timestamps and confidence from parsed segments" in {
    val segments = Seq(
      VoskSpeechToText.ParsedSegment(
        "hello world",
        List(
          WordTimestamp("hello", 0.0, 0.4, confidence = Some(0.8)),
          WordTimestamp("world", 0.5, 0.9, confidence = Some(0.6))
        )
      )
    )

    val transcription = VoskSpeechToText.buildTranscription(
      segments,
      STTOptions(language = Some("en-US"), enableTimestamps = true, confidenceThreshold = 0.5)
    )

    transcription.text shouldBe "hello world"
    transcription.language shouldBe Some("en-US")
    transcription.timestamps.map(_.word) shouldBe List("hello", "world")
    transcription.confidence shouldBe Some(0.7)
  }

  it should "fall back to segment text when filtering removes all words" in {
    val segments = Seq(
      VoskSpeechToText.ParsedSegment(
        "segment text",
        List(WordTimestamp("drop", 0.0, 0.3, confidence = Some(0.2)))
      )
    )

    val transcription = VoskSpeechToText.buildTranscription(
      segments,
      STTOptions(enableTimestamps = false, confidenceThreshold = 0.5)
    )

    transcription.text shouldBe "segment text"
    transcription.timestamps shouldBe Nil
    transcription.confidence shouldBe Some(0.2)
  }

  it should "decide when Vosk should request word metadata" in {
    VoskSpeechToText.shouldRequestWordMetadata(STTOptions()) shouldBe false
    VoskSpeechToText.shouldRequestWordMetadata(STTOptions(enableTimestamps = true)) shouldBe true
    VoskSpeechToText.shouldRequestWordMetadata(STTOptions(confidenceThreshold = 0.1)) shouldBe true
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

  it should "fall back to segment confidence and raw text when no word confidences survive" in {
    val json =
      """{
        |  "text": "segment text",
        |  "segments": [
        |    {
        |      "confidence": 0.66,
        |      "words": [
        |        { "word": "drop", "start": 0.0, "end": 0.2, "confidence": 0.2 }
        |      ]
        |    }
        |  ]
        |}""".stripMargin

    val parsed = WhisperSpeechToText.parseOutput(
      json,
      STTOptions(enableTimestamps = true, confidenceThreshold = 0.5)
    )

    parsed.text shouldBe "segment text"
    parsed.timestamps.map(_.word) shouldBe List("drop")
    parsed.confidence shouldBe Some(0.2)
  }

  it should "fall back to plain text output when stdout is not json" in {
    val parsed = WhisperSpeechToText.parseOutput("plain text output", STTOptions())

    parsed.text shouldBe "plain text output"
    parsed.language shouldBe None
    parsed.confidence shouldBe None
    parsed.timestamps shouldBe Nil
  }

  it should "use segment confidence when words do not provide any" in {
    val json =
      """{
        |  "text": "segment only",
        |  "segments": [
        |    {
        |      "probability": 0.73,
        |      "words": [
        |        { "word": "segment", "start": 0.0, "end": 0.4 }
        |      ]
        |    }
        |  ]
        |}""".stripMargin

    val parsed = WhisperSpeechToText.parseOutput(json, STTOptions(enableTimestamps = true))

    parsed.text shouldBe "segment"
    parsed.confidence shouldBe Some(0.73)
  }

  it should "force JSON output when timestamps are requested" in {
    WhisperSpeechToText.effectiveOutputFormat("txt", STTOptions(enableTimestamps = true)) shouldBe "json"
    WhisperSpeechToText.effectiveOutputFormat("txt", STTOptions()) shouldBe "txt"
  }

  it should "build whisper cli args from options" in {
    val inputPath = Files.createTempFile("whisper-args", ".wav")
    try {
      val args = WhisperSpeechToText.buildArgs(
        command = Seq("whisper"),
        model = "base",
        configuredOutputFormat = "txt",
        inputPath = inputPath,
        options = STTOptions(
          language = Some("en"),
          prompt = Some("hello"),
          enableTimestamps = true
        )
      )

      (args should contain).inOrderOnly(
        "whisper",
        inputPath.toString,
        "--model",
        "base",
        "--output_format",
        "json",
        "--language",
        "en",
        "--initial_prompt",
        "hello",
        "--word-timestamps"
      )
    } finally Files.deleteIfExists(inputPath)
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

  it should "fall back to the stem-based output path and finally stdout" in {
    val inputPath     = Files.createTempFile("whisper-stem", ".wav")
    val stemOutput    = inputPath.resolveSibling(inputPath.getFileName.toString.stripSuffix(".wav") + ".json")
    val stdoutPayload = "from stdout"

    Files.writeString(stemOutput, """{ "text": "from stem file" }""")

    try {
      WhisperSpeechToText.resolveCliOutput(inputPath, "json", stdoutPayload) shouldBe """{ "text": "from stem file" }"""
      Files.deleteIfExists(stemOutput)
      WhisperSpeechToText.resolveCliOutput(inputPath, "json", stdoutPayload) shouldBe stdoutPayload
    } finally {
      Files.deleteIfExists(stemOutput)
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

  it should "succeed for a fully valid batch" in {
    val options = Seq(
      STTOptions(language = Some("en-US"), confidenceThreshold = 0.3),
      STTOptions(language = Some("fr-FR"), prompt = Some("medical terms"))
    )

    STTOptions.validateBatch(options) shouldBe Right(options)
  }

  "WhisperSpeechToText.toTranscription" should "return an error for empty parsed text" in {
    val result = WhisperSpeechToText.toTranscription("""{ "text": "   " }""", STTOptions())

    result.isLeft shouldBe true
  }

  it should "build a transcription from parsed whisper output" in {
    val json =
      """{
        |  "text": "hello world",
        |  "language": "en",
        |  "words": [
        |    { "word": "hello", "start": 0.0, "end": 0.3, "confidence": 0.9 },
        |    { "word": "world", "start": 0.4, "end": 0.7, "confidence": 0.8 }
        |  ]
        |}""".stripMargin

    val result = WhisperSpeechToText.toTranscription(json, STTOptions(enableTimestamps = true))

    result.isRight shouldBe true
    val transcription = result.toOption.get
    transcription.language shouldBe Some("en")
    transcription.timestamps.map(_.word) shouldBe List("hello", "world")
    transcription.confidence shouldBe defined
    transcription.confidence.get shouldBe 0.85 +- 0.0001
  }
}
