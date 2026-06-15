package org.llm4s.speech.tts

import org.llm4s.http.{ HttpResponse, MockHttpClient, FailingHttpClient }
import org.llm4s.speech.AudioFormat
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.provider.ElevenLabsTTSClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ElevenLabsTTSClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = TTSConfig(
    provider = "elevenlabs",
    model = "eleven_multilingual_v2",
    voice = "21m00Tcm4TlvDq8ikWAM",
    apiKey = "el-test-key",
    baseUrl = "https://api.elevenlabs.io"
  )

  private val defaultOptions = TTSOptions(outputFormat = AudioFormat.WavPcm16)

  "ElevenLabsTTSClient" should "return GeneratedAudio on 200 OK response" in {
    val mock   = new MockHttpClient(HttpResponse(200, "fake-mp3-audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello ElevenLabs", defaultOptions)

    result.isRight shouldBe true
    val audio = result.toOption.get
    audio.data.length should be > 0
    audio.meta.sampleRate shouldBe 44100
    audio.meta.numChannels shouldBe 1
    audio.format shouldBe AudioFormat.WavPcm16
  }

  it should "include the voice ID in the URL path" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    client.synthesize("Hello", defaultOptions)

    mock.lastUrl shouldBe Some(s"https://api.elevenlabs.io/v1/text-to-speech/${cfg.voice}")
  }

  it should "use voice from TTSOptions when provided" in {
    val mock        = new MockHttpClient(HttpResponse(200, "audio"))
    val client      = ElevenLabsTTSClient.forTest(cfg, mock)
    val customVoice = "customVoiceId123"
    val options     = defaultOptions.copy(voice = Some(customVoice))

    client.synthesize("Hello", options)

    mock.lastUrl shouldBe Some(s"https://api.elevenlabs.io/v1/text-to-speech/$customVoice")
  }

  it should "use xi-api-key header for authentication" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    client.synthesize("Test", defaultOptions)

    mock.lastHeaders.get should contain("xi-api-key" -> "el-test-key")
  }

  it should "set Accept header for MP3" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    client.synthesize("Test", defaultOptions)

    mock.lastHeaders.get should contain("Accept" -> "audio/mpeg")
  }

  it should "include model_id and text in request body" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    client.synthesize("Say something", defaultOptions)

    val body = mock.lastBody.get
    body should include("eleven_multilingual_v2")
    body should include("Say something")
  }

  it should "return EngineNotAvailable error on 401 response" in {
    val mock   = new MockHttpClient(HttpResponse(401, """{"detail":{"status":"unauthorized"}}"""))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.EngineNotAvailable]
  }

  it should "return SynthesisFailed error on 422 response" in {
    val mock   = new MockHttpClient(HttpResponse(422, """{"detail":"Validation error"}"""))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "return SynthesisFailed error on 500 response" in {
    val mock   = new MockHttpClient(HttpResponse(500, """{"error":"Internal server error"}"""))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "return SynthesisFailed on network failure" in {
    val failing = new FailingHttpClient(new java.io.IOException("network timeout"))
    val client  = ElevenLabsTTSClient.forTest(cfg, failing)

    val result = client.synthesize("Hello", defaultOptions)

    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "have name 'elevenlabs-tts'" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)
    client.name shouldBe "elevenlabs-tts"
  }

  it should "send a POST request" in {
    val mock   = new MockHttpClient(HttpResponse(200, "audio"))
    val client = ElevenLabsTTSClient.forTest(cfg, mock)

    client.synthesize("Hello", defaultOptions)

    mock.postCallCount shouldBe 1
  }
}
