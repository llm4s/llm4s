package org.llm4s.speech.tts

import org.llm4s.error.ValidationError
import org.llm4s.speech.{ AudioFormat, StubHttpClient }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.tts.provider.AzureTTSClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class AzureTTSClientSpec extends AnyFlatSpec with Matchers {

  private val cfg = TTSConfig(
    "azure",
    "en-US-JennyNeural",
    "en-US-JennyNeural",
    "azure-key",
    "https://westus.tts.speech.microsoft.com",
    Some("westus")
  )

  "AzureTTSClient" should "return the response bytes untouched, described as 24 kHz 16-bit mono PCM" in {
    val http   = new StubHttpClient(body = StubHttpClient.binaryAudio)
    val client = new AzureTTSClient(cfg, http)

    val audio = client.synthesize("Hello", TTSOptions(outputFormat = AudioFormat.RawPcm16)).toOption.get

    audio.data shouldBe StubHttpClient.binaryAudio
    audio.meta.sampleRate shouldBe 24000
    audio.meta.numChannels shouldBe 1
    audio.meta.bitDepth shouldBe 16
    audio.format shouldBe AudioFormat.RawPcm16
  }

  it should "POST SSML with the subscription key and raw PCM output format" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new AzureTTSClient(cfg, http).synthesize("Hello")

    val request = http.only
    request.url shouldBe "https://westus.tts.speech.microsoft.com/cognitiveservices/v1"
    request.headers("Ocp-Apim-Subscription-Key") shouldBe "azure-key"
    request.headers("Content-Type") shouldBe "application/ssml+xml"
    request.headers("X-Microsoft-OutputFormat") shouldBe "raw-24khz-16bit-mono-pcm"
    request.text shouldBe
      "<speak version='1.0' xml:lang='en-US'><voice name='en-US-JennyNeural'>Hello</voice></speak>"
  }

  it should "XML-escape the text" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new AzureTTSClient(cfg, http).synthesize("""a < b & "c" > 'd'""")

    http.only.text should include("a &lt; b &amp; &quot;c&quot; &gt; &apos;d&apos;")
  }

  it should "reject a speaking rate outside Azure's documented 0.5 to 2 range without calling the service" in {
    Seq(0.4, 2.5, Double.NaN).foreach { rate =>
      val http   = new StubHttpClient(body = Array[Byte](1))
      val result = new AzureTTSClient(cfg, http).synthesize("Hello", TTSOptions(speakingRate = Some(rate)))

      result.left.toOption.get shouldBe a[ValidationError]
      http.requests shouldBe empty
    }
    Seq(0.5, 2.0).foreach { rate =>
      new AzureTTSClient(cfg, new StubHttpClient(body = Array[Byte](1)))
        .synthesize("Hello", TTSOptions(speakingRate = Some(rate)))
        .isRight shouldBe true
    }
  }

  it should "use the voice, language and speaking rate from TTSOptions" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new AzureTTSClient(cfg, http)
      .synthesize("Bonjour", TTSOptions(voice = Some("fr-FR-DeniseNeural"), speakingRate = Some(1.25)))
    new AzureTTSClient(cfg, http)
      .synthesize("Hola", TTSOptions(voice = Some("custom"), language = Some("es-ES")))

    http.requests(0).text shouldBe
      "<speak version='1.0' xml:lang='fr-FR'><voice name='fr-FR-DeniseNeural'><prosody rate='1.25'>Bonjour</prosody></voice></speak>"
    http.requests(1).text shouldBe
      "<speak version='1.0' xml:lang='es-ES'><voice name='custom'>Hola</voice></speak>"
  }

  it should "default the language to en-US for a voice without a locale" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new AzureTTSClient(cfg, http).synthesize("Hi", TTSOptions(voice = Some("custom")))

    http.only.text should include("xml:lang='en-US'")
  }

  it should "reject blank text without sending a request" in {
    val http = new StubHttpClient(body = Array[Byte](1))

    new AzureTTSClient(cfg, http).synthesize(" ").left.toOption.get shouldBe a[ValidationError]
    http.requests shouldBe empty
  }

  it should "fail on an empty audio body" in {
    new AzureTTSClient(cfg, new StubHttpClient()).synthesize("Hi").left.toOption.get shouldBe
      a[TTSError.SynthesisFailed]
  }

  it should "report its name" in {
    new AzureTTSClient(cfg, new StubHttpClient()).name shouldBe "azure-tts"
  }
}
