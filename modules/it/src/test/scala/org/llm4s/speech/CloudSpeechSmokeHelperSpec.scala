package org.llm4s.speech

import org.llm4s.it.tags.Local
import org.scalatest.EitherValues
import org.scalatest.exceptions.TestFailedException
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.charset.StandardCharsets.US_ASCII

/**
 * The `@Cloud` speech smoke suites are only as good as `CloudSpeechSmoke`'s assertions, and those
 * never run without a paid key. This offline suite proves they accept real-looking speech PCM and
 * refuse what a failing service returns (silence, a constant, a tiny error body, the wrong format).
 */
@Local
class CloudSpeechSmokeHelperSpec extends AnyFlatSpec with Matchers with EitherValues {

  private def pcm(samples: Seq[Int]): Array[Byte] = {
    val bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.foreach(s => bb.putShort(s.toShort))
    bb.array()
  }

  /** `seconds` of a two-tone signal at 24 kHz: audible, thousands of distinct sample values. */
  private def speechLike(seconds: Double, amp: Double = 8000): Array[Byte] =
    pcm((0 until (24000 * seconds).toInt).map { n =>
      math
        .round(amp * (0.6 * math.sin(2 * math.Pi * 220 * n / 24000) + 0.4 * math.sin(2 * math.Pi * 1330 * n / 24000)))
        .toInt
    })

  private def audio(
    data: Array[Byte],
    meta: AudioMeta = CloudSpeechSmoke.ExpectedMeta,
    f: AudioFormat = AudioFormat.WavPcm16
  ) =
    GeneratedAudio(data, meta, f)

  private def mustFail(a: GeneratedAudio, clue: String): Unit =
    withClue(clue)(an[TestFailedException] should be thrownBy CloudSpeechSmoke.requireSpeechPcm(a))

  "requireMp3" should "accept an ID3-tagged or frame-synced MP3 and refuse PCM, an error body and a wrong tag" in {
    val body                                                     = Array.fill[Byte](5000)(7)
    def mp3(head: Array[Byte], f: AudioFormat = AudioFormat.Mp3) = audio(head ++ body, f = f)
    CloudSpeechSmoke.requireMp3(mp3(Array('I'.toByte, 'D'.toByte, '3'.toByte)))
    CloudSpeechSmoke.requireMp3(mp3(Array(0xff.toByte, 0xfb.toByte)))
    an[TestFailedException] should be thrownBy CloudSpeechSmoke.requireMp3(audio(speechLike(2.0)))
    an[TestFailedException] should be thrownBy CloudSpeechSmoke.requireMp3(
      audio("""{"error":"bad key"}""".getBytes(US_ASCII) ++ body, f = AudioFormat.Mp3)
    )
    an[TestFailedException] should be thrownBy CloudSpeechSmoke.requireMp3(
      mp3(Array('I'.toByte, 'D'.toByte, '3'.toByte), AudioFormat.WavPcm16)
    )
  }

  "requireSpeechPcm" should "accept audible speech-length PCM and return a WAV that matches it" in {
    val data = speechLike(2.0)
    val wav  = CloudSpeechSmoke.requireSpeechPcm(audio(data))
    new String(wav, 0, 4, US_ASCII) shouldBe "RIFF"
    wav.length shouldBe 44 + data.length
    wav.drop(44) shouldBe data
  }

  it should "refuse what a failing or wrong service returns" in {
    mustFail(audio(new Array[Byte](96000)), "silence")
    mustFail(audio(pcm(Seq.fill(48000)(5000))), "a constant")
    mustFail(
      audio(pcm(Seq.tabulate(48000)(i => if ((i / 50) % 2 == 0) 8000 else -8000))),
      "a square wave (2 distinct values)"
    )
    mustFail(audio(speechLike(2.0, amp = 400)), "audible-but-quiet noise floor (peak under 1000)")
    mustFail(audio("""{"error":{"message":"invalid api key"}}""".getBytes(US_ASCII)), "an error body")
    mustFail(audio(speechLike(0.5)), "too short for the phrase")
    mustFail(audio(speechLike(25.0)), "too long for the phrase")
    mustFail(audio(speechLike(2.0).dropRight(1)), "an odd byte count (a torn sample)")
    mustFail(audio(speechLike(2.0), meta = AudioMeta(16000, 1, 16)), "the wrong sample rate in the metadata")
    mustFail(audio(speechLike(2.0), meta = AudioMeta(24000, 2, 16)), "stereo metadata")
    mustFail(audio(speechLike(2.0), f = AudioFormat.RawPcm16), "the wrong format tag")
  }

  it should "honour custom duration bounds" in {
    noException should be thrownBy CloudSpeechSmoke.requireSpeechPcm(audio(speechLike(0.5)), minSeconds = 0.2)
    a[TestFailedException] should be thrownBy CloudSpeechSmoke.requireSpeechPcm(
      audio(speechLike(3.0)),
      maxSeconds = 2.0
    )
  }

  "wav16k" should "produce a 16 kHz mono WAV about two thirds the length of the 24 kHz PCM" in {
    val data = speechLike(1.0)
    val wav  = CloudSpeechSmoke.wav16k(audio(data))
    val bb   = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
    new String(wav, 0, 4, US_ASCII) shouldBe "RIFF"
    bb.getInt(24) shouldBe 16000
    bb.getShort(22).toInt shouldBe 1
    bb.getShort(34).toInt shouldBe 16
    bb.getInt(40) shouldBe wav.length - 44
    math.abs(bb.getInt(40) - 32000) should be <= 8
  }
}
