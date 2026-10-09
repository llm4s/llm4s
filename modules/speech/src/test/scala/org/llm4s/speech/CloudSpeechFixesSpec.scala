package org.llm4s.speech

import org.llm4s.error._
import org.llm4s.http.Llm4sHttpClient
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.TTSOptions
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.{ ByteBuffer, ByteOrder }

/** Regression tests for defects found reviewing the cloud speech clients. */
@scala.annotation.nowarn("msg=Could not verify")
class CloudSpeechFixesSpec extends AnyFlatSpec with Matchers {

  // ------------------------------------------------------------ WAV header sample rate

  private def le32(v: Int): Array[Byte] = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()
  private def le16(v: Int): Array[Byte] =
    ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(v.toShort).array()
  private def ascii(s: String): Array[Byte] = s.getBytes("US-ASCII")

  private def chunk(id: String, payload: Array[Byte]): Array[Byte] =
    ascii(id) ++ le32(payload.length) ++ payload ++ (if (payload.length % 2 == 1) Array[Byte](0)
                                                     else Array.emptyByteArray)

  private def fmt(rate: Int, extensible: Boolean = false): Array[Byte] = {
    val base = le16(if (extensible) 0xfffe else 1) ++ le16(1) ++ le32(rate) ++ le32(rate * 2) ++ le16(2) ++ le16(16)
    chunk("fmt ", if (extensible) base ++ le16(22) ++ new Array[Byte](22) else base)
  }

  private def riff(chunks: Array[Byte]*): Array[Byte] = {
    val body = ascii("WAVE") ++ chunks.flatten
    ascii("RIFF") ++ le32(body.length) ++ body
  }

  private val data = chunk("data", Array.fill[Byte](40)(1))

  "CloudSpeechSupport.wavSampleRate" should "read the rate from chunks wherever fmt sits (table)" in {
    val table: Seq[(String, Array[Byte], Option[Int])] = Seq(
      ("canonical 16 kHz", riff(fmt(16000), data), Some(16000)),
      ("canonical 24 kHz", riff(fmt(24000), data), Some(24000)),
      ("44.1 kHz", riff(fmt(44100), data), Some(44100)),
      (
        "LIST chunk before fmt",
        riff(chunk("LIST", ascii("INFOISFT") ++ le32(4) ++ ascii("lav ")), fmt(44100), data),
        Some(44100)
      ),
      ("odd-sized chunk (padded) before fmt", riff(chunk("junk", Array[Byte](1, 2, 3)), fmt(22050), data), Some(22050)),
      ("WAVE_FORMAT_EXTENSIBLE fmt", riff(fmt(48000, extensible = true), data), Some(48000)),
      ("no fmt chunk", riff(data), None),
      ("RIFF but not WAVE", ascii("RIFF") ++ le32(4) ++ ascii("AVI ") ++ fmt(16000), None),
      ("truncated inside fmt", riff(fmt(16000)).take(30), None),
      ("not a RIFF file", Array.fill[Byte](100)(7), None),
      ("empty", Array.emptyByteArray, None),
      ("zero sample rate", riff(fmt(0), data), None)
    )
    table.foreach { case (label, bytes, expected) =>
      withClue(label)(CloudSpeechSupport.wavSampleRate(bytes) shouldBe expected)
    }
  }

  "AzureSTTClient" should "declare the rate of a WAV that has a LIST chunk before fmt" in {
    val wav  = riff(chunk("LIST", ascii("INFOISFT") ++ le32(4) ++ ascii("lav ")), fmt(44100), data)
    val http = StubHttpClient.json(200, """{"RecognitionStatus":"Success","DisplayText":"hi"}""")
    val cfg  = STTConfig("azure", "en-US", "k", "https://eastus.stt.speech.microsoft.com", Some("eastus"))

    new AzureSTTClient(cfg, http).transcribe(AudioInput.BytesAudio(wav, 16000))

    http.only.headers("Content-Type") shouldBe "audio/wav; codecs=audio/pcm; samplerate=44100"
  }

  // ------------------------------------------------------------ language tags

  "CloudSpeechSupport.primaryLanguage" should "take the ISO 639-1 prefix of BCP 47 and POSIX style tags" in {
    Seq("en-US" -> "en", "EN" -> "en", "zh-Hans-CN" -> "zh", "fr" -> "fr", "en_US" -> "en", "pt_BR" -> "pt").foreach {
      case (in, out) => withClue(in)(CloudSpeechSupport.primaryLanguage(in) shouldBe out)
    }
  }

  // ------------------------------------------------------------ secret scrubbing

  private val secretKey = "0123456789abcdef0123456789abcdef"
  private val ttsCfg    = TTSConfig("p", "m", "en-US-A", secretKey, "https://tts.example", Some("eastus"))
  private val sttCfg    = STTConfig("p", "m", secretKey, "https://stt.example", Some("eastus"))
  private val wav       = riff(fmt(16000), data)

  private val calls: Seq[(String, Llm4sHttpClient => Result[Any])] = Seq(
    "OpenAITTSClient"     -> (h => new OpenAITTSClient(ttsCfg, h).synthesize("hello")),
    "ElevenLabsTTSClient" -> (h => new ElevenLabsTTSClient(ttsCfg, h).synthesize("hello")),
    "AzureTTSClient"      -> (h => new AzureTTSClient(ttsCfg, h).synthesize("hello")),
    "OpenAISTTClient"     -> (h => new OpenAISTTClient(sttCfg, h).transcribe(AudioInput.BytesAudio(wav, 16000))),
    "AzureSTTClient"      -> (h => new AzureSTTClient(sttCfg, h).transcribe(AudioInput.BytesAudio(wav, 16000)))
  )

  for ((name, call) <- calls)
    s"$name" should "scrub its own key from an error body that echoes it (any status)" in {
      Seq(400, 401, 403, 429, 500).foreach { status =>
        val http = StubHttpClient.json(status, s"""{"error":{"message":"Invalid key $secretKey for this resource"}}""")
        val e    = call(http).left.toOption.get
        withClue(s"$status ")((e.message should not).include(secretKey))
        (e.toString should not).include(secretKey)
        e.context.values.foreach(v => (v should not).include(secretKey))
      }
    }

  // ------------------------------------------------------------ OpenAI request limits

  private val openAi = TTSConfig("openai", "tts-1", "alloy", "sk-test", "https://api.openai.com")

  "OpenAITTSClient" should "reject a speaking rate outside OpenAI's 0.25 to 4.0 without sending a request" in {
    Seq(0.0, 0.1, 0.2499, 4.0001, 5.0, -1.0, Double.NaN, Double.PositiveInfinity).foreach { rate =>
      val http = new StubHttpClient(body = Array[Byte](1))
      withClue(s"speed $rate ") {
        new OpenAITTSClient(openAi, http)
          .synthesize("hi", TTSOptions(speakingRate = Some(rate)))
          .left
          .toOption
          .get shouldBe
          a[ValidationError]
        http.requests shouldBe empty
      }
    }
  }

  it should "accept the speaking rate boundaries" in {
    Seq(0.25, 1.0, 4.0).foreach { rate =>
      val http = new StubHttpClient(body = Array[Byte](1))
      new OpenAITTSClient(openAi, http).synthesize("hi", TTSOptions(speakingRate = Some(rate))).isRight shouldBe true
      ujson.read(http.only.text)("speed").num shouldBe rate
    }
  }

  it should "reject input over OpenAI's 4096 characters, and accept exactly 4096" in {
    val http = new StubHttpClient(body = Array[Byte](1))
    val over = new OpenAITTSClient(openAi, http).synthesize("a" * 4097)
    over.left.toOption.get shouldBe a[ValidationError]
    http.requests shouldBe empty
    new OpenAITTSClient(openAi, http).synthesize("a" * 4096).isRight shouldBe true
    http.requests should have size 1
  }
}
