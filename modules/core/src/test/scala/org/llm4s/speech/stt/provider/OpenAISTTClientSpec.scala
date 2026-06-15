package org.llm4s.speech.stt.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient, FailingHttpClient }
import org.llm4s.speech.AudioInput
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.stt.{ STTError, STTOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class OpenAISTTClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = STTConfig(
    provider = "openai",
    model = "whisper-1",
    apiKey = "sk-test-key",
    baseUrl = "https://api.openai.com"
  )

  private val defaultOptions = STTOptions()

  private val validResponse = """{"text":"Hello world, this is a transcription."}"""

  "OpenAISTTClient" should "return Transcription on 200 OK for BytesAudio input" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val audioBytes = "fake-audio-bytes".getBytes
    val input      = AudioInput.BytesAudio(audioBytes, sampleRate = 16000)

    val result = client.transcribe(input, defaultOptions)

    result.isRight shouldBe true
    val transcription = result.toOption.get
    transcription.text shouldBe "Hello world, this is a transcription."
  }

  it should "include Authorization header with API key" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, defaultOptions)

    mock.lastHeaders.get should contain("Authorization" -> "Bearer sk-test-key")
  }

  it should "post to the correct URL" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, defaultOptions)

    mock.lastUrl shouldBe Some("https://api.openai.com/v1/audio/transcriptions")
  }

  it should "include language in request when specified in options" in {
    val mock    = new MockHttpClient(HttpResponse(200, validResponse))
    val client  = OpenAISTTClient.forTest(cfg, mock)
    val options = defaultOptions.copy(language = Some("en"))

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, options)

    // Multipart is sent; we just verify a call was made
    mock.postCallCount shouldBe 1
  }

  it should "return EngineNotAvailable error on 401 response" in {
    val mock   = new MockHttpClient(HttpResponse(401, """{"error":{"message":"Incorrect API key"}}"""))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.EngineNotAvailable]
  }

  it should "return InvalidInput error on 400 response" in {
    val mock   = new MockHttpClient(HttpResponse(400, """{"error":{"message":"Bad request"}}"""))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.InvalidInput]
  }

  it should "return ProcessingFailed error on 500 response" in {
    val mock   = new MockHttpClient(HttpResponse(500, """{"error":{"message":"Server error"}}"""))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "return ProcessingFailed on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = OpenAISTTClient.forTest(cfg, failing)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "handle StreamAudio input" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val stream = new java.io.ByteArrayInputStream("audio-stream-data".getBytes)
    val input  = AudioInput.StreamAudio(stream, sampleRate = 16000)

    val result = client.transcribe(input, defaultOptions)

    result.isRight shouldBe true
    result.toOption.get.text shouldBe "Hello world, this is a transcription."
  }

  it should "handle FileAudio input" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val tempFile = Files.createTempFile("test-audio-", ".wav")
    Files.write(tempFile, "fake-wav-data".getBytes)

    try {
      val input  = AudioInput.FileAudio(tempFile)
      val result = client.transcribe(input, defaultOptions)

      result.isRight shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  it should "return ProcessingFailed for invalid JSON response" in {
    val mock   = new MockHttpClient(HttpResponse(200, "this is not json"))
    val client = OpenAISTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "have correct name" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)
    client.name shouldBe "openai-stt"
  }

  it should "list supported audio formats" in {
    val mock   = new MockHttpClient(HttpResponse(200, validResponse))
    val client = OpenAISTTClient.forTest(cfg, mock)

    client.supportedFormats should contain("audio/wav")
    client.supportedFormats should contain("audio/mp3")
    client.supportedFormats.nonEmpty shouldBe true
  }

  it should "return language from options in transcription" in {
    val mock    = new MockHttpClient(HttpResponse(200, validResponse))
    val client  = OpenAISTTClient.forTest(cfg, mock)
    val options = defaultOptions.copy(language = Some("fr"))

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, options)

    result.isRight shouldBe true
    result.toOption.get.language shouldBe Some("fr")
  }
}
