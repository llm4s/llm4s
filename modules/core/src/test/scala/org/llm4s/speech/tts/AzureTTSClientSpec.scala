package org.llm4s.speech.tts

import org.llm4s.http.{ HttpResponse, MockHttpClient, FailingHttpClient }
import org.llm4s.speech.AudioFormat
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.provider.AzureTTSClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AzureTTSClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = TTSConfig(
    provider = "azure",
    model = "neural",
    voice = "en-US-JennyNeural",
    apiKey = "azure-speech-key",
    baseUrl = "default",
    region = Some("eastus")
  )

  private val defaultOptions = TTSOptions(outputFormat = AudioFormat.WavPcm16)

  "AzureTTSClient" should "return GeneratedAudio on 200 OK response" in {
    val mock   = new MockHttpClient(HttpResponse(200, "fake-azure-audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello Azure", defaultOptions)

    result.isRight shouldBe true
    val audio = result.toOption.get
    audio.data.length should be > 0
    audio.meta.sampleRate shouldBe 16000
    audio.meta.numChannels shouldBe 1
    audio.format shouldBe AudioFormat.WavPcm16
  }

  it should "use the correct Azure TTS URL with region" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    client.synthesize("Hello", defaultOptions)

    mock.lastUrl shouldBe Some("https://eastus.tts.speech.microsoft.com/cognitiveservices/v1")
  }

  it should "default to eastus region when no region is provided" in {
    val cfgNoRegion = cfg.copy(region = None)
    val mock        = new MockHttpClient(HttpResponse(200, "audio"))
    val client      = AzureTTSClient.forTest(cfgNoRegion, mock)

    client.synthesize("Hello", defaultOptions)

    mock.lastUrl shouldBe Some("https://eastus.tts.speech.microsoft.com/cognitiveservices/v1")
  }

  it should "use custom baseUrl when provided" in {
    val customCfg = cfg.copy(baseUrl = "https://custom-azure-endpoint.com")
    val mock      = new MockHttpClient(HttpResponse(200, "audio"))
    val client    = AzureTTSClient.forTest(customCfg, mock)

    client.synthesize("Hello", defaultOptions)

    mock.lastUrl shouldBe Some("https://custom-azure-endpoint.com/cognitiveservices/v1")
  }

  it should "use Ocp-Apim-Subscription-Key header for authentication" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    client.synthesize("Test", defaultOptions)

    mock.lastHeaders.get should contain("Ocp-Apim-Subscription-Key" -> "azure-speech-key")
  }

  it should "use SSML content type header" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    client.synthesize("Test", defaultOptions)

    mock.lastHeaders.get should contain("Content-Type" -> "application/ssml+xml")
  }

  it should "set MP3 output format header" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    client.synthesize("Test", defaultOptions)

    mock.lastHeaders.get should contain("X-Microsoft-OutputFormat" -> "audio-16khz-128kbitrate-mono-mp3")
  }

  it should "include SSML with voice name in request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    client.synthesize("Hello world", defaultOptions)

    val body = mock.lastBody.get
    body should include("en-US-JennyNeural")
    body should include("Hello world")
    body should include("<speak")
    body should include("<voice")
  }

  it should "use voice from TTSOptions when provided" in {
    val mock    = new MockHttpClient(HttpResponse(200, "audio"))
    val client  = AzureTTSClient.forTest(cfg, mock)
    val options = defaultOptions.copy(voice = Some("en-US-AriaNeural"))

    client.synthesize("Test", options)

    val body = mock.lastBody.get
    body should include("en-US-AriaNeural")
  }

  it should "escape XML special characters in text" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)

    client.synthesize("Hello & <World> \"test\" 'quote'", defaultOptions)

    val body = mock.lastBody.get
    body should include("&amp;")
    body should include("&lt;")
    body should include("&gt;")
    body should include("&quot;")
    body should include("&apos;")
  }

  it should "return EngineNotAvailable error on 401 response" in {
    val mock   = new MockHttpClient(HttpResponse(401, "Unauthorized"))
    val client = AzureTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.EngineNotAvailable]
  }

  it should "return SynthesisFailed error on 400 bad SSML response" in {
    val mock   = new MockHttpClient(HttpResponse(400, "Bad Request: Invalid SSML"))
    val client = AzureTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "return SynthesisFailed error on 500 response" in {
    val mock   = new MockHttpClient(HttpResponse(500, "Internal server error"))
    val client = AzureTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "return SynthesisFailed on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = AzureTTSClient.forTest(cfg, failing)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "have name 'azure-tts'" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = AzureTTSClient.forTest(cfg, mock)
    client.name shouldBe "azure-tts"
  }
}
