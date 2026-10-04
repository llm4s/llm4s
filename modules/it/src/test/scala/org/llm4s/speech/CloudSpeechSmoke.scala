package org.llm4s.speech

import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.processing.AudioPreprocessing
import org.scalatest.{ Assertions, EitherValues }
import org.scalatest.matchers.should.Matchers

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets.US_ASCII
import java.nio.file.Files

/**
 * Checks shared by the `@Cloud` speech smoke suites in this package.
 *
 * The cloud TTS clients return raw 24 kHz, 16-bit, mono PCM (see `docs/guide/speech.md`), so
 * "the service really returned speech" is asserted on structure that a wrong body cannot have:
 * the exact sample format, a plausible duration for the phrase, audible (non-silent, non-constant)
 * samples, and a WAV file whose header describes exactly the bytes it holds. An error page, an empty
 * body or a text-decoded (corrupted) body fails these.
 */
object CloudSpeechSmoke extends Assertions with Matchers with EitherValues {

  /** Short phrase every TTS smoke test speaks; "hello" is what the STT round trips look for. */
  val Phrase = "Hello world, this is a test of the LLM4S speech module."

  val ExpectedMeta: AudioMeta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16)

  private def samples(pcm: Array[Byte]): Array[Short] = {
    val buf = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
    Array.tabulate(pcm.length / 2)(i => buf.getShort(i * 2))
  }

  /**
   * Asserts `audio` is audible speech-length PCM in the format the clients promise, and that it
   * saves as a WAV file whose RIFF header matches the data.
   *
   * @return the WAV file's bytes
   */
  def requireSpeechPcm(audio: GeneratedAudio, minSeconds: Double = 1.0, maxSeconds: Double = 20.0): Array[Byte] = {
    audio.meta shouldBe ExpectedMeta
    audio.format shouldBe AudioFormat.WavPcm16
    audio.data.length % 2 shouldBe 0

    val seconds = audio.data.length.toDouble / (ExpectedMeta.sampleRate * 2)
    withClue(f"duration $seconds%.2fs for ${audio.data.length} bytes: ") {
      seconds should ((be >= minSeconds).and(be <= maxSeconds))
    }

    val pcm = samples(audio.data)
    withClue("a body of all-equal samples is silence or an error payload, not speech: ") {
      pcm.distinct.length should be > 100
      pcm.map(s => math.abs(s.toInt)).max should be > 1000
    }

    val file = Files.createTempFile("llm4s-cloud-smoke-", ".wav")
    try {
      WavFileGenerator.saveAsWav(audio, file).value
      val wav = Files.readAllBytes(file)
      val buf = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
      new String(wav, 0, 4, US_ASCII) shouldBe "RIFF"
      new String(wav, 8, 4, US_ASCII) shouldBe "WAVE"
      buf.getInt(24) shouldBe ExpectedMeta.sampleRate
      new String(wav, 36, 4, US_ASCII) shouldBe "data"
      buf.getInt(40) shouldBe audio.data.length
      wav.length shouldBe 44 + audio.data.length
      wav
    } finally Files.deleteIfExists(file)
  }

  /** `audio` as a 16 kHz mono WAV file's bytes, the rate the STT services take. */
  def wav16k(audio: GeneratedAudio): Array[Byte] = {
    val (pcm, meta) = AudioPreprocessing.resamplePcm16(audio.data, audio.meta, 16000).value
    WavFileGenerator.createWavHeader(pcm.length, meta).value ++ pcm
  }
}
