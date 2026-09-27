package org.llm4s.speech.tts

import org.llm4s.http.{ HttpResponse, MockHttpClient, FailingHttpClient }
import org.llm4s.speech.AudioFormat
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.provider.OpenAITTSClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpenAITTSClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = TTSConfig(
    provider = "openai",
    model = "tts-1",
    voice = "alloy",
    apiKey = "sk-test-key",
    baseUrl = "https://api.openai.com"
  )

  private val defaultOptions = TTSOptions(outputFormat = AudioFormat.WavPcm16)

  "OpenAITTSClient" should "return GeneratedAudio on 200 OK response" in {
    val audioData = "fake-mp3-audio-bytes"
    val mock      = new MockHttpClient(HttpResponse(200, audioData))
    val client    = OpenAITTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello world", defaultOptions)

    result.isRight shouldBe true
    val audio = result.toOption.get
    audio.data.length should be > 0
    audio.meta.sampleRate shouldBe 24000
    audio.meta.numChannels shouldBe 1
    audio.format shouldBe AudioFormat.WavPcm16
  }

  it should "use correct URL and headers in request" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio-bytes"))
    val client = OpenAITTSClient.forTest(cfg, mock)

    client.synthesize("Hello", defaultOptions)

    mock.lastUrl shouldBe Some("https://api.openai.com/v1/audio/speech")
    mock.lastHeaders.get should contain("Authorization" -> "Bearer sk-test-key")
    mock.lastHeaders.get should contain("Content-Type" -> "application/json")
  }

  it should "include model, voice and input in the request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio-bytes"))
    val client = OpenAITTSClient.forTest(cfg, mock)

    client.synthesize("Say this text", defaultOptions)

    val body = mock.lastBody.get
    body should include("tts-1")
    body should include("alloy")
    body should include("Say this text")
  }

  it should "use voice from TTSOptions when provided" in {
    val mock    = new MockHttpClient(HttpResponse(200, "audio-bytes"))
    val client  = OpenAITTSClient.forTest(cfg, mock)
    val options = defaultOptions.copy(voice = Some("fable"))

    client.synthesize("Test", options)

    val body = mock.lastBody.get
    body should include("fable")
  }

  it should "return EngineNotAvailable error on 401 response" in {
    val mock   = new MockHttpClient(HttpResponse(401, """{"error":{"message":"Incorrect API key"}}"""))
    val client = OpenAITTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.EngineNotAvailable]
  }

  it should "return SynthesisFailed error on 500 response" in {
    val mock   = new MockHttpClient(HttpResponse(500, """{"error":{"message":"Internal server error"}}"""))
    val client = OpenAITTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "return SynthesisFailed on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("connection refused"))
    val client  = OpenAITTSClient.forTest(cfg, failing)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "have name 'openai-tts'" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = OpenAITTSClient.forTest(cfg, mock)
    client.name shouldBe "openai-tts"
  }

  it should "send a POST request (not GET)" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = OpenAITTSClient.forTest(cfg, mock)

    client.synthesize("Hello", defaultOptions)

    mock.postCallCount shouldBe 1
  }

  it should "handle empty text synthesis" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio-bytes"))
    val client = OpenAITTSClient.forTest(cfg, mock)

    val result = client.synthesize("", defaultOptions)
    // The client should still call the API and return a result
    result.isRight shouldBe true
  }

  it should "use custom baseUrl from config" in {
    val customCfg = cfg.copy(baseUrl = "https://custom.openai.proxy.com")
    val mock      = new MockHttpClient(HttpResponse(200, "audio"))
    val client    = OpenAITTSClient.forTest(customCfg, mock)

    client.synthesize("Test", defaultOptions)

    mock.lastUrl shouldBe Some("https://custom.openai.proxy.com/v1/audio/speech")
  }
}
