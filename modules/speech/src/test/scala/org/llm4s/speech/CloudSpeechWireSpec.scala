package org.llm4s.speech.stt.provider

import org.llm4s.error._
import org.llm4s.speech._
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.stt.{ STTError, STTOptions }
import org.llm4s.speech.tts.{ TTSError, TTSOptions }
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.concurrent.duration._

/**
 * The cloud clients through the real JDK `Llm4sHttpClient` against a loopback server: what actually
 * crosses the socket (URL, headers, body bytes) and what comes back byte for byte. Offline.
 */
@scala.annotation.nowarn("msg=Could not verify")
class CloudSpeechWireSpec extends AnyFlatSpec with Matchers {

  import LocalHttpServer.{ json, ok, using }

  private val secretKey = "sk-secret-0123456789abcdefghijklmnop"

  /** 300 KB of pseudo-random bytes plus every byte value, so UTF-8-invalid sequences are guaranteed. */
  private val randomAudio: Array[Byte] = {
    val rnd = new scala.util.Random(42)
    val b   = new Array[Byte](300000)
    rnd.nextBytes(b)
    b ++ (0 until 256).map(_.toByte).toArray ++ Array[Byte](0, 0, -1, -1, -62, -128, -19, -96, -128)
  }

  private val nasty = "héllo 日本語 \"quoted\" back\\slash\nnew\r\nline\ttab 😀 </speak> & 'x'"

  private def tts(provider: String, base: String, voice: String = "alloy", model: String = "tts-1") =
    TTSConfig(provider, model, voice, secretKey, base, Some("eastus"))
  private def stt(provider: String, base: String, model: String = "whisper-1") =
    STTConfig(provider, model, secretKey, base, Some("eastus"))

  private def wavAt(rate: Int, pcm: Array[Byte]): Array[Byte] =
    WavFileGenerator.createWavHeader(pcm.length, AudioMeta(rate, 1, 16)).toOption.get ++ pcm

  // ---------------------------------------------------------------- TTS golden requests

  "OpenAITTSClient on the wire" should "send a golden JSON request, UTF-8 exact, with unicode, quotes and newlines" in {
    using(ok(Array[Byte](1, 2, 3))) { s =>
      new OpenAITTSClient(tts("openai", s.baseUrl), new RecordingHttpClient())
        .synthesize(nasty, TTSOptions(speakingRate = Some(1.25)))
        .isRight shouldBe true

      val r = s.only
      r.method shouldBe "POST"
      r.target shouldBe "/v1/audio/speech"
      r.header("Authorization") shouldBe Some(s"Bearer $secretKey")
      r.header("Content-Type") shouldBe Some("application/json")
      r.text shouldBe ujson.write(
        ujson.Obj(
          "model"           -> "tts-1",
          "input"           -> nasty,
          "voice"           -> "alloy",
          "response_format" -> "pcm",
          "speed"           -> 1.25
        )
      )
      ujson.read(r.body)("input").str shouldBe nasty
    }
  }

  it should "return binary audio byte for byte (no String round trip)" in {
    using(ok(randomAudio)) { s =>
      val audio = new OpenAITTSClient(tts("openai", s.baseUrl), new RecordingHttpClient())
        .synthesize("hi")
        .toOption
        .get
      audio.data shouldBe randomAudio
      (audio.meta.sampleRate, audio.meta.numChannels, audio.meta.bitDepth) shouldBe ((24000, 1, 16))
    }
  }

  "ElevenLabsTTSClient on the wire" should "URL-encode the voice id, request pcm_24000 and send golden JSON" in {
    using(ok(randomAudio)) { s =>
      val audio = new ElevenLabsTTSClient(
        tts("elevenlabs", s.baseUrl, voice = "v o/iéce+1", model = "eleven_multilingual_v2"),
        new RecordingHttpClient()
      ).synthesize(nasty).toOption.get

      val r = s.only
      r.target shouldBe "/v1/text-to-speech/v%20o%2Fi%C3%A9ce%2B1?output_format=pcm_24000"
      r.header("xi-api-key") shouldBe Some(secretKey)
      r.header("Content-Type") shouldBe Some("application/json")
      r.text shouldBe ujson.write(ujson.Obj("text" -> nasty, "model_id" -> "eleven_multilingual_v2"))
      audio.data shouldBe randomAudio
      (audio.meta.sampleRate, audio.meta.numChannels, audio.meta.bitDepth) shouldBe ((24000, 1, 16))
    }
  }

  it should "let TTSOptions.voice override the configured voice id" in {
    using(ok(Array[Byte](9))) { s =>
      new ElevenLabsTTSClient(tts("elevenlabs", s.baseUrl, voice = "cfg"), new RecordingHttpClient())
        .synthesize("x", TTSOptions(voice = Some("opt")))
      s.only.target should startWith("/v1/text-to-speech/opt?")
    }
  }

  "AzureTTSClient on the wire" should "send golden SSML with the subscription key and raw 24 kHz output format" in {
    using(ok(randomAudio)) { s =>
      val audio = new AzureTTSClient(
        tts("azure", s.baseUrl, voice = "en-GB-RyanNeural"),
        new RecordingHttpClient()
      ).synthesize("a < b & \"c\" 'd' > e", TTSOptions(speakingRate = Some(1.5))).toOption.get

      val r = s.only
      r.target shouldBe "/cognitiveservices/v1"
      r.header("Ocp-Apim-Subscription-Key") shouldBe Some(secretKey)
      r.header("Content-Type") shouldBe Some("application/ssml+xml")
      r.header("X-Microsoft-OutputFormat") shouldBe Some("raw-24khz-16bit-mono-pcm")
      r.text shouldBe "<speak version='1.0' xml:lang='en-GB'><voice name='en-GB-RyanNeural'>" +
        "<prosody rate='1.5'>a &lt; b &amp; &quot;c&quot; &apos;d&apos; &gt; e</prosody></voice></speak>"
      audio.data shouldBe randomAudio
      (audio.meta.sampleRate, audio.meta.numChannels, audio.meta.bitDepth) shouldBe ((24000, 1, 16))
    }
  }

  it should "send well-formed XML for unicode, newlines and markup-looking text" in {
    using(ok(Array[Byte](1))) { s =>
      new AzureTTSClient(tts("azure", s.baseUrl, voice = "en-US-JennyNeural"), new RecordingHttpClient())
        .synthesize(nasty)
      val doc = javax.xml.parsers.DocumentBuilderFactory
        .newInstance()
        .newDocumentBuilder()
        .parse(new java.io.ByteArrayInputStream(s.only.body))
      doc.getElementsByTagName("voice").item(0).getTextContent shouldBe nasty.replace("\r\n", "\n")
    }
  }

  it should "default xml:lang to en-US for a voice name without a locale" in {
    using(ok(Array[Byte](1))) { s =>
      new AzureTTSClient(tts("azure", s.baseUrl, voice = "SomeVoice"), new RecordingHttpClient()).synthesize("x")
      s.only.text should include("xml:lang='en-US'")
    }
  }

  // ---------------------------------------------------------------- TTS errors over the wire

  private val ttsCalls: Seq[(String, String => Either[LLMError, Any])] = Seq(
    "openai" -> (b => new OpenAITTSClient(tts("openai", b), new RecordingHttpClient()).synthesize("hi")),
    "elevenlabs" -> (b =>
      new ElevenLabsTTSClient(tts("elevenlabs", b, "v"), new RecordingHttpClient()).synthesize("hi")
    ),
    "azure" -> (b => new AzureTTSClient(tts("azure", b, "en-US-A"), new RecordingHttpClient()).synthesize("hi"))
  )

  for ((name, call) <- ttsCalls) {
    s"The $name TTS client" should "map every HTTP status over the wire (table)" in {
      val table: Seq[(Int, LLMError => Boolean)] = Seq(
        400 -> (_.isInstanceOf[ValidationError]),
        401 -> (_.isInstanceOf[AuthenticationError]),
        403 -> (_.isInstanceOf[AuthenticationError]),
        404 -> (_.isInstanceOf[ServiceError]),
        422 -> (_.isInstanceOf[ServiceError]),
        429 -> (_.isInstanceOf[RateLimitError]),
        500 -> (_.isInstanceOf[ServiceError]),
        502 -> (_.isInstanceOf[ServiceError]),
        503 -> (_.isInstanceOf[ServiceError])
      )
      table.foreach { case (status, check) =>
        using(json(status, """{"error":{"message":"nope"}}""")) { s =>
          val e = call(s.baseUrl).left.toOption.get
          withClue(s"status $status -> $e: ")(check(e) shouldBe true)
        }
      }
    }

    it should "parse a numeric Retry-After on 429 and ignore garbage" in {
      using(_ => WireReply(429, Array.emptyByteArray, Map("Retry-After" -> "11"))) { s =>
        call(s.baseUrl).left.toOption.get.asInstanceOf[RateLimitError].retryAfter shouldBe Some(11.seconds)
      }
      using(_ => WireReply(429, Array.emptyByteArray, Map("Retry-After" -> "soon"))) { s =>
        call(s.baseUrl).left.toOption.get.asInstanceOf[RateLimitError].retryAfter shouldBe None
      }
    }

    it should "return a NetworkError when the connection is refused" in {
      val dead = new LocalHttpServer(ok(Array[Byte](1)))
      val base = dead.baseUrl
      dead.close()
      call(base).left.toOption.get shouldBe a[NetworkError]
    }
  }

  "TTS clients" should "report a 200 with an empty body as SynthesisFailed, not as empty audio" in {
    using(_ => WireReply(200, Array.emptyByteArray)) { s =>
      call0(new OpenAITTSClient(tts("openai", s.baseUrl), new RecordingHttpClient())) shouldBe a[
        TTSError.SynthesisFailed
      ]
    }
  }

  private def call0(c: org.llm4s.speech.tts.TextToSpeech): LLMError = c.synthesize("hi").left.toOption.get

  // ---------------------------------------------------------------- STT multipart

  /** Splits a multipart body on its boundary: part headers (as text) and the raw content bytes. */
  private def parseMultipart(r: WireRequest): Seq[(String, Array[Byte])] = {
    val boundary = r.header("Content-Type").get.split("boundary=")(1)
    val delim    = s"--$boundary".getBytes(StandardCharsets.UTF_8)
    def indexOf(hay: Array[Byte], needle: Array[Byte], from: Int): Int =
      (from to hay.length - needle.length).find(i => needle.indices.forall(j => hay(i + j) == needle(j))).getOrElse(-1)
    val crlf2 = "\r\n\r\n".getBytes(StandardCharsets.UTF_8)
    val body  = r.body
    val marks =
      Iterator.iterate(indexOf(body, delim, 0))(i => indexOf(body, delim, i + delim.length)).takeWhile(_ >= 0).toList
    marks.zip(marks.drop(1)).map { case (a, b) =>
      val start = a + delim.length + 2                                // CRLF after the boundary
      val hEnd  = indexOf(body, crlf2, start)
      val head  = new String(body, start, hEnd - start, StandardCharsets.UTF_8)
      val data  = java.util.Arrays.copyOfRange(body, hEnd + 4, b - 2) // CRLF before next boundary
      head -> data
    }
  }

  private def field(parts: Seq[(String, Array[Byte])], name: String): Option[String] =
    parts.collectFirst {
      case (h, d) if h.contains(s"""name="$name"""") && !h.contains("filename=") =>
        new String(d, StandardCharsets.UTF_8)
    }

  "OpenAISTTClient on the wire" should "upload a binary-safe multipart body with fields, boundary and bearer token" in {
    using(json(200, """{"text":"hello"}""")) { s =>
      // The audio contains the CRLF + dashes sequence of a multipart delimiter, and 0x00/0xFF bytes.
      val pcm = randomAudio ++ "\r\n--not-the-boundary--\r\n".getBytes(StandardCharsets.UTF_8)
      val wav = wavAt(16000, pcm)
      val res = new OpenAISTTClient(stt("openai", s.baseUrl), new RecordingHttpClient())
        .transcribe(
          AudioInput.BytesAudio(wav, 16000),
          STTOptions(language = Some("en-US"), prompt = Some("say \"hi\"\r\nline é日"))
        )
      res.toOption.get.text shouldBe "hello"

      val r = s.only
      r.target shouldBe "/v1/audio/transcriptions"
      r.header("Authorization") shouldBe Some(s"Bearer $secretKey")
      r.header("Content-Type").get should startWith("multipart/form-data; boundary=")
      val parts = parseMultipart(r)
      val file  = parts.find(_._1.contains("""name="file"""")).get
      file._1 should include("""filename="llm4s-openai-stt-""")
      file._1 should include(".wav\"")
      file._2 shouldBe wav
      field(parts, "model") shouldBe Some("whisper-1")
      field(parts, "language") shouldBe Some("en")
      field(parts, "prompt") shouldBe Some("say \"hi\"\r\nline é日")
    }
  }

  it should "use a different boundary for every request" in {
    using(json(200, """{"text":"hello"}""")) { s =>
      val c = new OpenAISTTClient(stt("openai", s.baseUrl), new RecordingHttpClient())
      c.transcribe(AudioInput.BytesAudio(wavAt(16000, Array[Byte](1, 2)), 16000))
      c.transcribe(AudioInput.BytesAudio(wavAt(16000, Array[Byte](1, 2)), 16000))
      s.requests.map(_.header("Content-Type").get).distinct should have size 2
    }
  }

  it should "send timestamp_granularities[] only when timestamps are enabled" in {
    using(json(200, """{"text":"hello"}""")) { s =>
      val c = new OpenAISTTClient(stt("openai", s.baseUrl), new RecordingHttpClient())
      c.transcribe(AudioInput.BytesAudio(wavAt(16000, Array[Byte](1, 2)), 16000))
      c.transcribe(AudioInput.BytesAudio(wavAt(16000, Array[Byte](1, 2)), 16000), STTOptions(enableTimestamps = true))
      val (plain, stamped) = (parseMultipart(s.requests(0)), parseMultipart(s.requests(1)))
      field(plain, "response_format") shouldBe None
      field(plain, "timestamp_granularities[]") shouldBe None
      field(stamped, "response_format") shouldBe Some("verbose_json")
      field(stamped, "timestamp_granularities[]") shouldBe Some("word")
    }
  }

  private def tempLeftovers(http: RecordingHttpClient): Seq[Path] = http.uploads.filter(Files.exists(_))

  it should "delete its staged temp file on success and on every failure path" in {
    val wav = wavAt(16000, Array.fill[Byte](100)(3))
    val outcomes: Seq[(String, WireRequest => WireReply)] = Seq(
      "success"    -> json(200, """{"text":"ok"}"""),
      "empty text" -> json(200, """{"text":""}"""),
      "bad json"   -> json(200, "<html>"),
      "401"        -> json(401, "{}"),
      "429"        -> json(429, "{}"),
      "500"        -> json(500, "{}")
    )
    outcomes.foreach { case (label, reply) =>
      using(reply) { s =>
        val http = new RecordingHttpClient()
        new OpenAISTTClient(stt("openai", s.baseUrl), http).transcribe(AudioInput.BytesAudio(wav, 16000))
        withClue(s"$label: ") {
          http.uploads should have size 1
          tempLeftovers(http) shouldBe empty
        }
      }
    }
    // Connection refused: the request never reaches a server.
    val dead = new LocalHttpServer(ok(Array.emptyByteArray))
    val base = dead.baseUrl
    dead.close()
    val http = new RecordingHttpClient()
    new OpenAISTTClient(stt("openai", base), http).transcribe(AudioInput.BytesAudio(wav, 16000)).isLeft shouldBe true
    http.uploads should have size 1
    tempLeftovers(http) shouldBe empty
  }

  it should "never delete or modify the caller's own file, even when the call fails" in {
    using(json(500, "{}")) { s =>
      val file = Files.createTempFile("llm4s-caller-", ".wav")
      try {
        val wav = wavAt(16000, Array.fill[Byte](10)(1))
        Files.write(file, wav)
        new OpenAISTTClient(stt("openai", s.baseUrl), new RecordingHttpClient()).transcribe(AudioInput.FileAudio(file))
        Files.readAllBytes(file) shouldBe wav
      } finally Files.deleteIfExists(file)
    }
  }

  it should "upload a caller file under its own (escaped-by-name-only) file name, not a path" in {
    using(json(200, """{"text":"ok"}""")) { s =>
      val dir  = Files.createTempDirectory("llm4s-dir-")
      val file = dir.resolve("a.wav")
      try {
        Files.write(file, Array[Byte](1, 2, 3))
        new OpenAISTTClient(stt("openai", s.baseUrl), new RecordingHttpClient()).transcribe(AudioInput.FileAudio(file))
        val head = parseMultipart(s.only).find(_._1.contains("name=\"file\"")).get._1
        head should include("filename=\"a.wav\"")
        (head should not).include(dir.toString)
      } finally {
        Files.deleteIfExists(file)
        Files.deleteIfExists(dir)
      }
    }
  }

  it should "parse word timestamps defensively: missing fields, nulls, ints, negative or inverted spans" in {
    val body =
      """{"text":"a b c d e f","language":"fr","words":[
        |{"word":"ok","start":0,"end":1},
        |{"word":"nostart","end":1.0},
        |{"word":"nullend","start":0.0,"end":null},
        |{"word":"  ","start":0.0,"end":1.0},
        |{"word":"neg","start":-1.0,"end":1.0},
        |{"word":"inv","start":2.0,"end":1.0},
        |{"start":0.0,"end":1.0},
        |{"word":"edge","start":3.5,"end":3.5}]}""".stripMargin
    OpenAISTTClient
      .parse(body, STTOptions(enableTimestamps = true))
      .toOption
      .get
      .timestamps
      .map(w => (w.word, w.startSec, w.endSec)) shouldBe List(("ok", 0.0, 1.0), ("edge", 3.5, 3.5))
  }

  it should "report a null or missing text as a processing failure, not an exception" in {
    OpenAISTTClient.parse("""{"text":null}""", STTOptions()).left.toOption.get shouldBe a[STTError.ProcessingFailed]
    OpenAISTTClient.parse("""{"nothing":1}""", STTOptions()).left.toOption.get shouldBe a[STTError.ProcessingFailed]
    OpenAISTTClient.parse("""[1,2]""", STTOptions()).left.toOption.get shouldBe a[STTError.ProcessingFailed]
  }

  it should "report the detected language only when none was requested" in {
    OpenAISTTClient.parse("""{"text":"x","language":"german"}""", STTOptions()).toOption.get.language shouldBe
      Some("german")
    OpenAISTTClient
      .parse("""{"text":"x","language":"german"}""", STTOptions(language = Some("de-DE")))
      .toOption
      .get
      .language shouldBe Some("de-DE")
  }

  // ---------------------------------------------------------------- Azure STT

  "AzureSTTClient on the wire" should "post the exact WAV bytes with key, content type and encoded language" in {
    using(json(200, """{"RecognitionStatus":"Success","DisplayText":"Hi."}""")) { s =>
      val wav = wavAt(8000, randomAudio)
      val res = new AzureSTTClient(stt("azure", s.baseUrl, "en-US"), new RecordingHttpClient())
        .transcribe(AudioInput.BytesAudio(wav, 16000), STTOptions(language = Some("zh-CN&format=detailed #x")))
      res.toOption.get.text shouldBe "Hi."

      val r = s.only
      r.target shouldBe
        "/speech/recognition/conversation/cognitiveservices/v1?language=zh-CN%26format%3Ddetailed+%23x&format=simple"
      r.header("Ocp-Apim-Subscription-Key") shouldBe Some(secretKey)
      r.header("Content-Type") shouldBe Some("audio/wav; codecs=audio/pcm; samplerate=8000")
      r.header("Accept") shouldBe Some("application/json")
      r.body shouldBe wav
    }
  }

  it should "map every RecognitionStatus variant" in {
    def run(body: String) = AzureSTTClient.parse(body, "en-US")
    run("""{"RecognitionStatus":"Success","DisplayText":" Hello. "}""").toOption.get.text shouldBe "Hello."
    val failing = Seq(
      """{"RecognitionStatus":"Success","DisplayText":""}""" -> "empty transcription",
      """{"RecognitionStatus":"Success"}"""                  -> "empty transcription",
      """{"RecognitionStatus":"NoMatch"}"""                  -> "no speech",
      """{"RecognitionStatus":"InitialSilenceTimeout"}"""    -> "silence",
      """{"RecognitionStatus":"BabbleTimeout"}"""            -> "BabbleTimeout",
      """{"RecognitionStatus":"Error"}"""                    -> "Error",
      """{"DisplayText":"orphan text"}"""                    -> "Unknown",
      """{"RecognitionStatus":null,"DisplayText":"x"}"""     -> "Unknown"
    )
    failing.foreach { case (body, needle) =>
      withClue(body) {
        val e = run(body).left.toOption.get
        e shouldBe a[STTError.ProcessingFailed]
        e.message should include(needle)
      }
    }
    run("<html>").left.toOption.get.message should include("Failed to parse Azure STT response")
  }

  it should "map HTTP failures over the wire" in {
    val wav = wavAt(16000, Array.fill[Byte](10)(1))
    Seq(
      400 -> classOf[ValidationError],
      401 -> classOf[AuthenticationError],
      429 -> classOf[RateLimitError],
      500 -> classOf[ServiceError]
    )
      .foreach { case (status, cls) =>
        using(json(status, s"""{"error":{"message":"bad request"}}""")) { s =>
          val e = new AzureSTTClient(stt("azure", s.baseUrl, "en-US"), new RecordingHttpClient())
            .transcribe(AudioInput.BytesAudio(wav, 16000))
            .left
            .toOption
            .get
          e.getClass shouldBe cls
        }
      }
  }
}
