package org.llm4s.speech.tts

import org.llm4s.error.ValidationError
import org.llm4s.speech.{ AudioFormat, AudioMeta, GeneratedAudio, StubHttpClient }
import org.llm4s.speech.config.TTSConfig
import org.llm4s.speech.io.{ AudioIO, WavFileGenerator }
import org.llm4s.speech.processing.{ AudioPreprocessing, AudioValidator }
import org.llm4s.speech.tts.provider.{ AzureTTSClient, ElevenLabsTTSClient, OpenAITTSClient }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import scala.util.Using

/** Opt-in MP3 output: the exact format parameter per client, bytes untouched, and PCM helpers refusing it. */
class Mp3PassthroughSpec extends AnyFlatSpec with Matchers {

  private val mp3Bytes = Array[Byte]('I', 'D', '3', 3, 0, 0, 0, 0, 0, 0, 1, 2, 3, 4)
  private val mp3      = TTSOptions(outputFormat = AudioFormat.Mp3)
  private val mp3Audio = GeneratedAudio(mp3Bytes, AudioMeta(24000, 1, 0), AudioFormat.Mp3)

  private val openai = TTSConfig("openai", "tts-1", "alloy", "sk-test-key", "https://api.openai.com")
  private val eleven =
    TTSConfig("elevenlabs", "eleven_multilingual_v2", "voice123", "el-key", "https://api.elevenlabs.io")
  private val azure =
    TTSConfig("azure", "azure", "en-US-JennyNeural", "az-key", "https://eastus.tts.speech.microsoft.com")

  "OpenAITTSClient" should "request response_format=mp3 and return the bytes untouched" in {
    val http  = new StubHttpClient(body = mp3Bytes)
    val audio = new OpenAITTSClient(openai, http).synthesize("Hi", mp3).toOption.get

    ujson.read(http.only.text)("response_format").str shouldBe "mp3"
    audio.data shouldBe mp3Bytes
    audio.format shouldBe AudioFormat.Mp3
    audio.meta shouldBe AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 0)
  }

  it should "keep requesting pcm by default" in {
    val http = new StubHttpClient(body = mp3Bytes)
    new OpenAITTSClient(openai, http).synthesize("Hi")
    ujson.read(http.only.text)("response_format").str shouldBe "pcm"
  }

  "ElevenLabsTTSClient" should "request output_format=mp3_44100_128 and return the bytes untouched" in {
    val http  = new StubHttpClient(body = mp3Bytes)
    val audio = new ElevenLabsTTSClient(eleven, http).synthesize("Hi", mp3).toOption.get

    http.only.url shouldBe "https://api.elevenlabs.io/v1/text-to-speech/voice123?output_format=mp3_44100_128"
    audio.data shouldBe mp3Bytes
    audio.format shouldBe AudioFormat.Mp3
    audio.meta shouldBe AudioMeta(sampleRate = 44100, numChannels = 1, bitDepth = 0)
  }

  it should "keep requesting pcm_24000 by default" in {
    val http = new StubHttpClient(body = mp3Bytes)
    new ElevenLabsTTSClient(eleven, http).synthesize("Hi")
    http.only.url should endWith("output_format=pcm_24000")
  }

  "AzureTTSClient" should "send the mp3 X-Microsoft-OutputFormat and return the bytes untouched" in {
    val http  = new StubHttpClient(body = mp3Bytes)
    val audio = new AzureTTSClient(azure, http).synthesize("Hi", mp3).toOption.get

    http.only.headers("X-Microsoft-OutputFormat") shouldBe "audio-24khz-48kbitrate-mono-mp3"
    audio.data shouldBe mp3Bytes
    audio.format shouldBe AudioFormat.Mp3
    audio.meta shouldBe AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 0)
  }

  it should "keep requesting raw PCM by default" in {
    val http = new StubHttpClient(body = mp3Bytes)
    new AzureTTSClient(azure, http).synthesize("Hi")
    http.only.headers("X-Microsoft-OutputFormat") shouldBe "raw-24khz-16bit-mono-pcm"
  }

  "Tacotron2TextToSpeech" should "refuse MP3 instead of labelling PCM as MP3" in {
    val result = new Tacotron2TextToSpeech(Seq("definitely-not-installed")).synthesize("Hi", mp3)
    result.left.toOption.get shouldBe a[ValidationError]
  }

  "PCM helpers" should "reject MP3 audio with a ValidationError" in {
    val dir = Files.createTempDirectory("llm4s-mp3-spec")
    try {
      WavFileGenerator.saveAsWav(mp3Audio, dir.resolve("a.wav")).left.toOption.get shouldBe a[ValidationError]
      AudioIO.saveWav(mp3Audio, dir.resolve("b.wav")).left.toOption.get shouldBe a[ValidationError]
      AudioIO.saveRawPcm16(mp3Audio, dir.resolve("c.pcm")).left.toOption.get shouldBe a[ValidationError]
      AudioPreprocessing.standardizeForSTT(mp3Audio, 16000).left.toOption.get shouldBe a[ValidationError]
      entryCount(dir) shouldBe 0
    } finally deleteDir(dir)
  }

  "An MP3 AudioMeta" should "not claim a sample width, so PCM code that is handed it anyway refuses it" in {
    Seq(OpenAITTSClient.Mp3Meta, ElevenLabsTTSClient.Mp3Meta, AzureTTSClient.Mp3Meta).foreach(_.bitDepth shouldBe 0)

    // Bypass the format guard by relabelling the audio as PCM: the metadata alone must still be refused.
    val dir = Files.createTempDirectory("llm4s-mp3-meta")
    try {
      val relabelled = mp3Audio.copy(format = AudioFormat.WavPcm16)
      val error      = WavFileGenerator.saveAsWav(relabelled, dir.resolve("a.wav")).left.toOption.get
      error.message should include("Bit depth")
      entryCount(dir) shouldBe 0
    } finally deleteDir(dir)
  }

  it should "be refused by the STT validators with an error, not an ArithmeticException" in {
    val input = mp3Audio.data -> mp3Audio.meta
    Seq(
      AudioValidator.sttValidator.validate(input),
      AudioValidator.AudioDataValidator().validate(input),
      AudioValidator.validatedSttValidatorAsResult(input)
    ).foreach(result => result.left.toOption.get.message should include("no PCM frame size"))
  }

  "AudioIO.saveMp3" should "write the bytes as they are, and refuse PCM" in {
    val file = Files.createTempFile("llm4s-mp3-spec", ".mp3")
    try {
      AudioIO.saveMp3(mp3Audio, file) shouldBe Right(file)
      Files.readAllBytes(file) shouldBe mp3Bytes
      AudioIO
        .saveMp3(mp3Audio.copy(format = AudioFormat.WavPcm16), file)
        .left
        .toOption
        .get shouldBe a[ValidationError]
    } finally Files.deleteIfExists(file)
  }

  "GeneratedAudio" should "report PCM formats as PCM" in {
    mp3Audio.isPcm shouldBe false
    mp3Audio.copy(format = AudioFormat.RawPcm16).isPcm shouldBe true
    mp3Audio.copy(format = AudioFormat.WavPcm16).requirePcm("x").isRight shouldBe true
  }

  // Files.list holds a directory handle until its stream is closed, which on Windows blocks deleting the directory.
  private def entryCount(dir: Path): Long = Using.resource(Files.list(dir))(_.count())

  private def deleteDir(dir: Path): Unit = {
    Using.resource(Files.list(dir))(_.forEach(p => Files.deleteIfExists(p)))
    Files.deleteIfExists(dir)
  }
}
