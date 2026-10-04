package org.llm4s.speech.stt.provider

import org.llm4s.speech.{ AudioInput, AudioMeta, StubHttpClient }
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.stt.{ STTError, STTOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class OpenAISTTClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = STTConfig("openai", "whisper-1", "sk-test-key", "https://api.openai.com")

  private val pcm = Array.fill[Byte](3200)(7)
  private val wav =
    WavFileGenerator.createWavHeader(pcm.length, AudioMeta(16000, 1, 16)).toOption.get ++ pcm

  "OpenAISTTClient" should "upload the WAV bytes as a file and return the transcription" in {
    val http   = StubHttpClient.json(200, """{"text":"  hello world "}""")
    val client = new OpenAISTTClient(cfg, http)

    val result = client.transcribe(AudioInput.BytesAudio(wav, 16000)).toOption.get

    result.text shouldBe "hello world"
    result.timestamps shouldBe empty
    val request = http.only
    request.url shouldBe "https://api.openai.com/v1/audio/transcriptions"
    request.headers("Authorization") shouldBe "Bearer sk-test-key"
    request.field("model") shouldBe Some("whisper-1")
    request.fileContents("file") shouldBe wav
  }

  it should "delete the temporary file it staged for byte input" in {
    val http = StubHttpClient.json(200, """{"text":"hi"}""")

    new OpenAISTTClient(cfg, http).transcribe(AudioInput.BytesAudio(wav, 16000))

    Files.exists(http.only.filePaths("file")) shouldBe false
  }

  it should "upload a file input in place and leave it alone" in {
    val file = Files.createTempFile("llm4s-stt-spec-", ".wav")
    try {
      Files.write(file, wav)
      val http = StubHttpClient.json(200, """{"text":"hi"}""")

      new OpenAISTTClient(cfg, http).transcribe(AudioInput.FileAudio(file))

      http.only.filePaths("file") shouldBe file
      http.only.fileContents("file") shouldBe wav
      Files.exists(file) shouldBe true
    } finally Files.deleteIfExists(file)
  }

  it should "read stream input" in {
    val http = StubHttpClient.json(200, """{"text":"hi"}""")

    new OpenAISTTClient(cfg, http).transcribe(AudioInput.StreamAudio(new java.io.ByteArrayInputStream(wav), 16000))

    http.only.fileContents("file") shouldBe wav
  }

  it should "send the ISO 639-1 language and the prompt" in {
    val http = StubHttpClient.json(200, """{"text":"bonjour"}""")

    val result = new OpenAISTTClient(cfg, http)
      .transcribe(AudioInput.BytesAudio(wav, 16000), STTOptions(language = Some("fr-FR"), prompt = Some("greetings")))

    result.toOption.get.language shouldBe Some("fr-FR")
    http.only.field("language") shouldBe Some("fr")
    http.only.field("prompt") shouldBe Some("greetings")
    http.only.field("response_format") shouldBe None
  }

  it should "request word timestamps and parse them" in {
    val http = StubHttpClient.json(
      200,
      """{"text":"hello world","language":"english","words":[
        |  {"word":"hello","start":0.0,"end":0.4},
        |  {"word":"world","start":0.5,"end":0.9},
        |  {"word":"bad","start":2.0,"end":1.0}]}""".stripMargin
    )

    val result = new OpenAISTTClient(cfg, http)
      .transcribe(AudioInput.BytesAudio(wav, 16000), STTOptions(enableTimestamps = true))
      .toOption
      .get

    http.only.field("response_format") shouldBe Some("verbose_json")
    http.only.field("timestamp_granularities[]") shouldBe Some("word")
    result.timestamps.map(w => (w.word, w.startSec, w.endSec)) shouldBe
      List(("hello", 0.0, 0.4), ("world", 0.5, 0.9))
    result.language shouldBe Some("english")
  }

  it should "refuse word timestamps for a model other than whisper-1 (OpenAI supports them only there)" in {
    val http = StubHttpClient.json(200, """{"text":"hi"}""")

    val result = new OpenAISTTClient(cfg.copy(model = "gpt-4o-transcribe"), http)
      .transcribe(AudioInput.BytesAudio(wav, 16000), STTOptions(enableTimestamps = true))

    result.left.toOption.get.message should include("whisper-1")
    http.requests shouldBe empty
    new OpenAISTTClient(cfg.copy(model = "gpt-4o-transcribe"), http)
      .transcribe(AudioInput.BytesAudio(wav, 16000))
      .isRight shouldBe true
  }

  it should "fail on an empty transcription" in {
    val result = new OpenAISTTClient(cfg, StubHttpClient.json(200, """{"text":"  "}"""))
      .transcribe(AudioInput.BytesAudio(wav, 16000))

    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "fail on a body that is not the expected JSON" in {
    val result = new OpenAISTTClient(cfg, StubHttpClient.json(200, "not json"))
      .transcribe(AudioInput.BytesAudio(wav, 16000))

    result.left.toOption.get.message should include("Failed to parse OpenAI STT response")
  }

  it should "report a missing input file as an error" in {
    val missing = java.nio.file.Paths.get("/nonexistent/llm4s/audio.wav")
    val result =
      new OpenAISTTClient(cfg, StubHttpClient.json(200, """{"text":"hi"}""")).transcribe(AudioInput.FileAudio(missing))

    result.left.toOption.get shouldBe a[org.llm4s.error.ValidationError]
  }

  it should "report its name and formats" in {
    val client = new OpenAISTTClient(cfg, new StubHttpClient())
    client.name shouldBe "openai-stt"
    client.supportedFormats should contain("audio/wav")
  }
}
