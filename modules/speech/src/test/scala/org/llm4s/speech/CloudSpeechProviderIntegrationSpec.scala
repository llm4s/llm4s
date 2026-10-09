package org.llm4s.speech

import org.llm4s.speech.config.{ STTConfig, TTSConfig }
import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.processing.AudioPreprocessing
import org.llm4s.speech.stt.provider.{ AzureSTTClient, OpenAISTTClient }
import org.llm4s.speech.tts.TextToSpeech
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

/**
 * The cloud TTS clients and the audio pipeline around them, end to end with the HTTP layer
 * stubbed (no network, no keys): provider PCM -> `GeneratedAudio` -> WAV file -> resample -> STT
 * upload. The live-API counterparts are the `@Cloud` smoke suites in `modules/it`
 * (`org.llm4s.speech`), which assert the same structure against the real services.
 */
class CloudSpeechProviderIntegrationSpec extends AnyFlatSpec with Matchers with EitherValues {

  private val SampleRate = 24000

  /** Half a second of a 440 Hz tone as 16-bit little-endian mono PCM. */
  private val tone: Array[Byte] = {
    val samples = SampleRate / 2
    val buf     = ByteBuffer.allocate(samples * 2).order(ByteOrder.LITTLE_ENDIAN)
    (0 until samples).foreach(i => buf.putShort((math.sin(2 * math.Pi * 440 * i / SampleRate) * 12000).toShort))
    buf.array()
  }

  private val tts = TTSConfig("p", "m", "v", "k", "https://tts.example", Some("eastus"))

  private val clients: Seq[(String, Array[Byte] => TextToSpeech)] = Seq(
    "OpenAITTSClient"     -> (pcm => new OpenAITTSClient(tts, new StubHttpClient(body = pcm))),
    "ElevenLabsTTSClient" -> (pcm => new ElevenLabsTTSClient(tts, new StubHttpClient(body = pcm))),
    "AzureTTSClient"      -> (pcm => new AzureTTSClient(tts, new StubHttpClient(body = pcm)))
  )

  private def le(bytes: Array[Byte], offset: Int, length: Int): Int = {
    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    if (length == 2) buf.getShort(offset).toInt else buf.getInt(offset)
  }

  for ((name, client) <- clients) {

    s"$name audio" should "save as a WAV file whose header describes exactly the PCM it holds" in {
      val audio = client(tone).synthesize("hello world").value
      val file  = Files.createTempFile("llm4s-cloud-tts-", ".wav")
      try {
        WavFileGenerator.saveAsWav(audio, file).value
        val wav = Files.readAllBytes(file)

        new String(wav, 0, 4, "US-ASCII") shouldBe "RIFF"
        new String(wav, 8, 4, "US-ASCII") shouldBe "WAVE"
        le(wav, 22, 2) shouldBe 1 // channels
        le(wav, 24, 4) shouldBe SampleRate
        le(wav, 34, 2) shouldBe 16 // bits per sample
        new String(wav, 36, 4, "US-ASCII") shouldBe "data"
        le(wav, 40, 4) shouldBe tone.length
        wav.length shouldBe 44 + tone.length
        wav.drop(44) shouldBe tone

        val roundTrip = WavFileGenerator.readWavFile(file).value
        roundTrip.data shouldBe tone
        roundTrip.meta shouldBe AudioMeta(SampleRate, 1, 16)
      } finally Files.deleteIfExists(file)
    }

    it should "resample to 16 kHz, a third shorter" in {
      val audio = client(tone).synthesize("hello world").value

      val (pcm, meta) = AudioPreprocessing.resamplePcm16(audio.data, audio.meta, 16000).value

      meta.sampleRate shouldBe 16000
      // 12000 frames at 24 kHz are 8000 at 16 kHz, 2 bytes each; allow the converter a few frames.
      pcm.length should ((be >= 15900).and(be <= 16100))
    }
  }

  "A synthesised WAV" should "be uploaded intact and at its own sample rate by both STT clients" in {
    val audio = new OpenAITTSClient(tts, new StubHttpClient(body = tone)).synthesize("hello world").value
    val wav   = WavFileGenerator.createWavHeader(audio.data.length, audio.meta).value ++ audio.data

    val openAi = StubHttpClient.json(200, """{"text":"hello world"}""")
    new OpenAISTTClient(STTConfig("openai", "whisper-1", "k", "https://stt.example"), openAi)
      .transcribe(AudioInput.BytesAudio(wav, SampleRate))
      .value
      .text shouldBe "hello world"
    openAi.only.fileContents("file") shouldBe wav

    val azure = StubHttpClient.json(200, """{"RecognitionStatus":"Success","DisplayText":"Hello world."}""")
    new AzureSTTClient(STTConfig("azure", "en-US", "k", "https://stt.example", Some("eastus")), azure)
      .transcribe(AudioInput.BytesAudio(wav, SampleRate))
      .value
      .text shouldBe "Hello world."
    azure.only.body shouldBe wav
    azure.only.headers("Content-Type") shouldBe "audio/wav; codecs=audio/pcm; samplerate=24000"
  }
}
