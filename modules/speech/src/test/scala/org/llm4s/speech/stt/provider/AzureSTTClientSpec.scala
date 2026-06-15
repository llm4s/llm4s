package org.llm4s.speech.stt.provider

import org.llm4s.http.{ HttpResponse, MockHttpClient, FailingHttpClient }
import org.llm4s.speech.AudioInput
import org.llm4s.speech.config.STTConfig
import org.llm4s.speech.stt.{ STTError, STTOptions }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.Files

class AzureSTTClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = STTConfig(
    provider = "azure",
    model = "conversation",
    apiKey = "azure-speech-key",
    baseUrl = "default",
    region = Some("eastus")
  )

  private val defaultOptions = STTOptions()

  private val successResponse =
    """{"RecognitionStatus":"Success","DisplayText":"Hello from Azure STT.","Offset":0,"Duration":100000}"""

  "AzureSTTClient" should "return Transcription on 200 OK for BytesAudio input" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input = AudioInput.BytesAudio("fake-audio".getBytes, sampleRate = 16000)

    val result = client.transcribe(input, defaultOptions)

    result.isRight shouldBe true
    result.toOption.get.text shouldBe "Hello from Azure STT."
  }

  it should "use the correct Azure STT URL with region" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, defaultOptions)

    mock.lastUrl shouldBe Some(
      "https://eastus.stt.speech.microsoft.com/speech/recognition/conversation/cognitiveservices/v1?language=en-US"
    )
  }

  it should "use custom language in URL from options" in {
    val mock    = new MockHttpClient(HttpResponse(200, successResponse))
    val client  = AzureSTTClient.forTest(cfg, mock)
    val options = defaultOptions.copy(language = Some("fr-FR"))

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, options)

    mock.lastUrl.get should include("language=fr-FR")
  }

  it should "default to eastus region when no region is provided" in {
    val cfgNoRegion = cfg.copy(region = None)
    val mock        = new MockHttpClient(HttpResponse(200, successResponse))
    val client      = AzureSTTClient.forTest(cfgNoRegion, mock)

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, defaultOptions)

    mock.lastUrl.get should include("eastus.stt.speech.microsoft.com")
  }

  it should "use custom baseUrl when provided" in {
    val customCfg = cfg.copy(baseUrl = "https://custom-azure-stt.example.com")
    val mock      = new MockHttpClient(HttpResponse(200, successResponse))
    val client    = AzureSTTClient.forTest(customCfg, mock)

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, defaultOptions)

    mock.lastUrl.get should startWith("https://custom-azure-stt.example.com")
  }

  it should "use Ocp-Apim-Subscription-Key header for authentication" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    client.transcribe(input, defaultOptions)

    mock.lastHeaders.get should contain("Ocp-Apim-Subscription-Key" -> "azure-speech-key")
  }

  it should "return EngineNotAvailable error on 401 response" in {
    val mock   = new MockHttpClient(HttpResponse(401, "Unauthorized"))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.EngineNotAvailable]
  }

  it should "return InvalidInput error on 400 response" in {
    val mock   = new MockHttpClient(HttpResponse(400, "Bad Request"))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.InvalidInput]
  }

  it should "return ProcessingFailed error on 500 response" in {
    val mock   = new MockHttpClient(HttpResponse(500, "Internal Server Error"))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "return ProcessingFailed on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = AzureSTTClient.forTest(cfg, failing)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "handle NoMatch recognition status" in {
    val noMatchResponse = """{"RecognitionStatus":"NoMatch","Offset":0,"Duration":10000}"""
    val mock            = new MockHttpClient(HttpResponse(200, noMatchResponse))
    val client          = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("silence".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
    result.left.toOption.get.message should include("No speech could be recognized")
  }

  it should "handle InitialSilenceTimeout recognition status" in {
    val silenceResponse = """{"RecognitionStatus":"InitialSilenceTimeout","Offset":0,"Duration":0}"""
    val mock            = new MockHttpClient(HttpResponse(200, silenceResponse))
    val client          = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("silent-audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
    result.left.toOption.get.message should include("silence")
  }

  it should "handle unknown recognition status" in {
    val unknownResponse = """{"RecognitionStatus":"BabbleTimeout","Offset":0,"Duration":0}"""
    val mock            = new MockHttpClient(HttpResponse(200, unknownResponse))
    val client          = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "return ProcessingFailed on invalid JSON response" in {
    val mock   = new MockHttpClient(HttpResponse(200, "not valid json"))
    val client = AzureSTTClient.forTest(cfg, mock)

    val input  = AudioInput.BytesAudio("audio".getBytes, sampleRate = 16000)
    val result = client.transcribe(input, defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "handle StreamAudio input" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)

    val stream = new java.io.ByteArrayInputStream("audio-stream-data".getBytes)
    val input  = AudioInput.StreamAudio(stream, sampleRate = 16000)

    val result = client.transcribe(input, defaultOptions)

    result.isRight shouldBe true
    result.toOption.get.text shouldBe "Hello from Azure STT."
  }

  it should "handle FileAudio input" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)

    val tempFile = Files.createTempFile("test-azure-stt-", ".wav")
    Files.write(tempFile, "fake-wav-data".getBytes)

    try {
      val input  = AudioInput.FileAudio(tempFile)
      val result = client.transcribe(input, defaultOptions)

      result.isRight shouldBe true
    } finally Files.deleteIfExists(tempFile)
  }

  it should "have correct name" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)
    client.name shouldBe "azure-stt"
  }

  it should "list supported audio formats" in {
    val mock   = new MockHttpClient(HttpResponse(200, successResponse))
    val client = AzureSTTClient.forTest(cfg, mock)

    client.supportedFormats should contain("audio/wav")
    client.supportedFormats.nonEmpty shouldBe true
  }
}
