package org.llm4s.speech.tts

import org.llm4s.error.ValidationError
import org.llm4s.speech.{ AudioFormat, StubHttpClient }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.provider.ElevenLabsTTSClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ElevenLabsTTSClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = TTSConfig("elevenlabs", "eleven_multilingual_v2", "voice123", "el-key", "https://api.elevenlabs.io")

  "ElevenLabsTTSClient" should "return the response bytes untouched, described as 24 kHz 16-bit mono PCM" in {
    val http   = new StubHttpClient(body = StubHttpClient.binaryAudio)
    val client = new ElevenLabsTTSClient(cfg, http)

    val audio = client.synthesize("Hello", TTSOptions(outputFormat = AudioFormat.RawPcm16)).toOption.get

    audio.data shouldBe StubHttpClient.binaryAudio
    audio.meta.sampleRate shouldBe 24000
    audio.meta.numChannels shouldBe 1
    audio.meta.bitDepth shouldBe 16
    audio.format shouldBe AudioFormat.RawPcm16
  }

  it should "POST to the voice endpoint with pcm output, the API key header and model_id" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new ElevenLabsTTSClient(cfg, http).synthesize("Hello there")

    val request = http.only
    request.url shouldBe "https://api.elevenlabs.io/v1/text-to-speech/voice123?output_format=pcm_24000"
    request.headers("xi-api-key") shouldBe "el-key"
    val json = ujson.read(request.text)
    json("text").str shouldBe "Hello there"
    json("model_id").str shouldBe "eleven_multilingual_v2"
  }

  it should "use and URL-encode the voice from TTSOptions" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new ElevenLabsTTSClient(cfg, http).synthesize("Hello", TTSOptions(voice = Some("my voice/1")))

    http.only.url should startWith("https://api.elevenlabs.io/v1/text-to-speech/my%20voice%2F1?")
  }

  it should "reject blank text without sending a request" in {
    val http   = new StubHttpClient(body = Array[Byte](1))
    val result = new ElevenLabsTTSClient(cfg, http).synthesize("")

    result.left.toOption.get shouldBe a[ValidationError]
    http.requests shouldBe empty
  }

  it should "fail on an empty audio body" in {
    new ElevenLabsTTSClient(cfg, new StubHttpClient()).synthesize("Hi").left.toOption.get shouldBe
      a[TTSError.SynthesisFailed]
  }

  it should "report its name" in {
    new ElevenLabsTTSClient(cfg, new StubHttpClient()).name shouldBe "elevenlabs-tts"
  }
}
