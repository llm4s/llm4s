package org.llm4s.speech

import org.llm4s.config.ReferenceConfig
import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.processing.AudioPreprocessing
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayInputStream
import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import scala.util.{ Try, Using }
import javax.sound.sampled.AudioSystem

/**
 * The offline twin of the `@Cloud` smoke suites, through the real HTTP client against loopback
 * servers: the same flow (TTS bytes -> `GeneratedAudio` -> WAV -> 16 kHz -> STT upload) with the
 * service replaced by a server that returns a known 440 Hz tone, so what reaches the "STT
 * service" can be decoded and measured instead of only compared with stub bytes.
 */
class CloudSpeechPipelineWireSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val Rate = 24000

  private val tone: Array[Byte] = {
    val bb = ByteBuffer.allocate(Rate * 2).order(ByteOrder.LITTLE_ENDIAN) // one second
    (0 until Rate).foreach(i => bb.putShort(math.round(12000 * math.sin(2 * math.Pi * 440 * i / Rate)).toShort))
    bb.array()
  }

  private def ttsConfig(provider: String, base: String, voice: String) =
    TTSConfig(provider, "tts-1", voice, "key", base, Some("eastus"))

  /** The part of a multipart body after the `filename=` part headers, up to the next delimiter. */
  private def uploadedFile(req: WireRequest): Array[Byte] = {
    val body = req.body
    def find(needle: Array[Byte], from: Int): Int =
      (from to body.length - needle.length)
        .find(i => needle.indices.forall(j => body(i + j) == needle(j)))
        .getOrElse(-1)
    val boundary = req.header("Content-Type").get.split("boundary=")(1)
    val fileAt   = find("filename=\"".getBytes(StandardCharsets.UTF_8), 0)
    val start    = find("\r\n\r\n".getBytes(StandardCharsets.UTF_8), fileAt) + 4
    val end      = find(s"\r\n--$boundary".getBytes(StandardCharsets.UTF_8), start)
    java.util.Arrays.copyOfRange(body, start, end)
  }

  private def rising(samples: Array[Short]): Int = samples.sliding(2).count(p => p(0) < 0 && p(1) >= 0)

  private val synthesizers: Seq[(String, String => org.llm4s.speech.tts.TextToSpeech)] = Seq(
    "OpenAI"     -> (b => new OpenAITTSClient(ttsConfig("openai", b, "alloy"))),
    "ElevenLabs" -> (b => new ElevenLabsTTSClient(ttsConfig("elevenlabs", b, "voice123"))),
    "Azure"      -> (b => new AzureTTSClient(ttsConfig("azure", b, "en-US-JennyNeural")))
  )

  for ((name, mk) <- synthesizers)
    s"$name TTS -> WAV -> 16 kHz -> OpenAI STT over HTTP" should "upload a decodable 16 kHz mono WAV of the same 440 Hz tone" in {
      LocalHttpServer.using(LocalHttpServer.ok(tone)) { tts =>
        LocalHttpServer.using(LocalHttpServer.json(200, """{"text":"hello world"}""")) { stt =>
          val audio = mk(tts.baseUrl).synthesize("hello world").value

          // The documented save path yields a valid file the JDK itself can read.
          val file = Files.createTempFile("llm4s-pipeline-wire-", ".wav")
          try {
            WavFileGenerator.saveAsWav(audio, file).value
            // getAudioInputStream(File) holds the file open until closed; Windows cannot delete an open file.
            val saved = Using.resource(AudioSystem.getAudioInputStream(file.toFile))(_.getFormat)
            (saved.getSampleRate.toInt, saved.getChannels, saved.getSampleSizeInBits) shouldBe ((Rate, 1, 16))
          } finally {
            // Cleanup must not mask the real assertion failure.
            val _ = Try(Files.deleteIfExists(file))
          }

          val (pcm16k, meta) = AudioPreprocessing.resamplePcm16(audio.data, audio.meta, 16000).value
          val wav            = WavFileGenerator.createWavHeader(pcm16k.length, meta).value ++ pcm16k
          new OpenAISTTClient(STTConfig("openai", "whisper-1", "key", stt.baseUrl))
            .transcribe(AudioInput.BytesAudio(wav, 16000))
            .value
            .text shouldBe "hello world"

          val sent = uploadedFile(stt.only)
          sent shouldBe wav
          Using.resource(AudioSystem.getAudioInputStream(new ByteArrayInputStream(sent))) { decoded =>
            val fmt = decoded.getFormat
            (fmt.getSampleRate.toInt, fmt.getChannels, fmt.getSampleSizeInBits) shouldBe ((16000, 1, 16))
            math.abs(decoded.getFrameLength - 16000L) should be <= 2L
          }
          val samples = ByteBuffer.wrap(sent.drop(44)).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
          val arr     = new Array[Short](samples.remaining())
          samples.get(arr)
          math.abs(rising(arr) - 440) should be <= 2
        }
      }
    }

  "A TTS -> Azure STT round trip over HTTP" should "declare the sample rate of the WAV it sends" in {
    LocalHttpServer.using(LocalHttpServer.ok(tone)) { tts =>
      LocalHttpServer.using(
        LocalHttpServer.json(200, """{"RecognitionStatus":"Success","DisplayText":"Hello world."}""")
      ) { stt =>
        val audio = new AzureTTSClient(ttsConfig("azure", tts.baseUrl, "en-US-JennyNeural")).synthesize("hi").value
        val wav   = WavFileGenerator.createWavHeader(audio.data.length, audio.meta).value ++ audio.data
        val text = new AzureSTTClient(STTConfig("azure", "en-US", "key", stt.baseUrl, Some("eastus")))
          .transcribe(AudioInput.BytesAudio(wav, 8000))
          .value
          .text
        text shouldBe "Hello world."
        stt.only.header("Content-Type") shouldBe Some("audio/wav; codecs=audio/pcm; samplerate=24000")
        stt.only.body shouldBe wav
      }
    }
  }

  "The smoke suites' configuration path" should "yield clients pointed at the configured base URL" in {
    LocalHttpServer.using(LocalHttpServer.ok(tone)) { tts =>
      val env = ReferenceConfig.withEnv(
        "",
        Map("OPENAI_API_KEY" -> "k", "OPENAI_SPEECH_BASE_URL" -> tts.baseUrl, "SPEECH_TTS_MODEL" -> "openai/tts-1")
      )
      val client = SpeechProviderSelector.tts(env).value
      client.synthesize("hello").value.data shouldBe tone
      tts.only.target shouldBe "/v1/audio/speech"
    }
  }
}
