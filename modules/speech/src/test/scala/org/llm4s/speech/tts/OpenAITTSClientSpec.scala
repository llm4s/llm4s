package org.llm4s.speech.tts

import org.llm4s.error.ValidationError
import org.llm4s.speech.{ AudioFormat, StubHttpClient }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.provider.OpenAITTSClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class OpenAITTSClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = TTSConfig("openai", "tts-1", "alloy", "sk-test-key", "https://api.openai.com")

  "OpenAITTSClient" should "return the response bytes untouched, described as 24 kHz 16-bit mono PCM" in {
    val http   = new StubHttpClient(body = StubHttpClient.binaryAudio)
    val client = new OpenAITTSClient(cfg, http)

    val audio = client.synthesize("Hello world", TTSOptions(outputFormat = AudioFormat.RawPcm16)).toOption.get

    audio.data shouldBe StubHttpClient.binaryAudio
    audio.meta.sampleRate shouldBe 24000
    audio.meta.numChannels shouldBe 1
    audio.meta.bitDepth shouldBe 16
    audio.format shouldBe AudioFormat.RawPcm16
  }

  it should "refuse a blank apiKey before any request, rather than send an empty bearer" in {
    for key <- Seq("", "  ") do
      val http = new StubHttpClient(body = Array[Byte](1, 2, 3))
      new OpenAITTSClient(cfg.copy(apiKey = key), http)
        .synthesize("Say this text")
        .left
        .toOption
        .get shouldBe a[org.llm4s.error.ConfigurationError]
      http.requests shouldBe empty
  }

  it should "POST model, voice, input and the pcm response format with a bearer token" in {
    val http   = new StubHttpClient(body = Array[Byte](1, 2, 3))
    val client = new OpenAITTSClient(cfg, http)

    client.synthesize("Say this text")

    val request = http.only
    request.url shouldBe "https://api.openai.com/v1/audio/speech"
    request.headers("Authorization") shouldBe "Bearer sk-test-key"
    val json = ujson.read(request.text)
    json("model").str shouldBe "tts-1"
    json("voice").str shouldBe "alloy"
    json("input").str shouldBe "Say this text"
    json("response_format").str shouldBe "pcm"
    json.obj.contains("speed") shouldBe false
  }

  it should "prefer the voice and speaking rate in TTSOptions" in {
    val http   = new StubHttpClient(body = Array[Byte](1))
    val client = new OpenAITTSClient(cfg, http)

    client.synthesize("Hi", TTSOptions(voice = Some("nova"), speakingRate = Some(1.5)))

    val json = ujson.read(http.only.text)
    json("voice").str shouldBe "nova"
    json("speed").num shouldBe 1.5
  }

  it should "reject blank text without sending a request" in {
    val http   = new StubHttpClient(body = Array[Byte](1))
    val result = new OpenAITTSClient(cfg, http).synthesize("   ")

    result.left.toOption.get shouldBe a[ValidationError]
    http.requests shouldBe empty
  }

  it should "fail on an empty audio body" in {
    val result = new OpenAITTSClient(cfg, new StubHttpClient()).synthesize("Hi")

    result.left.toOption.get shouldBe a[TTSError.SynthesisFailed]
  }

  it should "report its name" in {
    new OpenAITTSClient(cfg, new StubHttpClient()).name shouldBe "openai-tts"
  }
}
