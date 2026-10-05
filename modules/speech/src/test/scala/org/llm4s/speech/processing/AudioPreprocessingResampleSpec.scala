package org.llm4s.speech.processing

import org.llm4s.error.ValidationError
import org.llm4s.speech.AudioMeta
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `resamplePcm16` through its public API.
 *
 * Every call goes through [[TimedCalls.timed]], so a call that never returns fails its test in seconds instead of
 * hanging the build.
 */
class AudioPreprocessingResampleSpec extends AnyFlatSpec with Matchers with TimedCalls {

  private val mono16 = AudioMeta(24000, 1, 16)

  private def resample(bytes: Array[Byte], source: AudioMeta, target: Int) =
    timed()(AudioPreprocessing.resamplePcm16(bytes, source, target))

  private def sine(frequency: Double, rate: Int, frames: Int, channels: Int = 1): Array[Byte] = {
    val out = new Array[Byte](frames * channels * 2)
    (0 until frames).foreach { i =>
      val sample = (math.sin(2 * math.Pi * frequency * i / rate) * 12000).toInt
      (0 until channels).foreach { ch =>
        val at = (i * channels + ch) * 2
        out(at) = (sample & 0xff).toByte
        out(at + 1) = ((sample >> 8) & 0xff).toByte
      }
    }
    out
  }

  private def samples(bytes: Array[Byte]): Vector[Int] =
    Vector.tabulate(bytes.length / 2)(i => ((bytes(2 * i + 1) << 8) | (bytes(2 * i) & 0xff)).toShort.toInt)

  private def zeroCrossings(bytes: Array[Byte]): Int =
    samples(bytes).sliding(2).count(pair => pair.length == 2 && (pair(0) < 0) != (pair(1) < 0))

  private def rms(bytes: Array[Byte]): Double = {
    val s = samples(bytes)
    math.sqrt(s.map(x => x.toDouble * x).sum / s.size)
  }

  private def validationField(result: org.llm4s.types.Result[?]): String =
    result.left.toOption match {
      case Some(error: ValidationError) => error.field
      case other                        => fail(s"expected a ValidationError, got $other")
    }

  private val audio = sine(440, 24000, 2400)

  // --- the arguments that used to hang or to "succeed" with a nonsense rate -------------------------------------

  "resamplePcm16" should "reject a target rate that is zero, negative or beyond the supported maximum" in {
    List(0, -1, -8000, Int.MinValue, 768001, Int.MaxValue).foreach { rate =>
      withClue(s"target rate $rate: ")(validationField(resample(audio, mono16, rate)) shouldBe "targetRate")
    }
  }

  it should "reject a source rate that is zero, negative or beyond the supported maximum" in {
    List(0, -1, Int.MinValue, 768001).foreach { rate =>
      withClue(s"source rate $rate: ")(
        validationField(resample(audio, mono16.copy(sampleRate = rate), 16000)) shouldBe "source.sampleRate"
      )
    }
  }

  it should "reject a source with no channels, too many channels, or a sample size that is not whole bytes" in {
    List(0, -2, 65).foreach { channels =>
      withClue(s"$channels channels: ")(
        validationField(resample(audio, mono16.copy(numChannels = channels), 16000)) shouldBe "source.numChannels"
      )
    }
    List(0, 4, 12, 40, -16).foreach { depth =>
      withClue(s"bit depth $depth: ")(
        validationField(resample(audio, mono16.copy(bitDepth = depth), 16000)) shouldBe "source.bitDepth"
      )
    }
  }

  it should "accept the lowest and the highest supported target rates" in {
    resample(audio, mono16, 1).isRight shouldBe true
    resample(sine(440, 24000, 100), mono16, 768000).isRight shouldBe true
  }

  it should "refuse an output that would not fit in a byte array, without allocating it" in {
    // 1 Hz to 768 kHz turns a million frames into 7.7e11: far beyond any array
    val oneHertz = AudioMeta(1, 1, 16)
    validationField(resample(new Array[Byte](2000000), oneHertz, 768000)) shouldBe "targetRate"
  }

  // --- the length contract ---------------------------------------------------------------------------------------

  it should "return empty output for empty input" in {
    resample(Array.emptyByteArray, mono16, 16000) shouldBe Right(
      (Array.emptyByteArray, mono16.copy(sampleRate = 16000))
    )
  }

  it should "return empty output when the input is too short to make a single output frame" in {
    resample(Array[Byte](1, 2), AudioMeta(48000, 1, 16), 8000).map(_._1.length) shouldBe Right(0)
  }

  it should "ignore a trailing partial frame, as toMono and trimSilence do" in {
    resample(Array[Byte](1), mono16, 16000).map(_._1.length) shouldBe Right(0)
    resample(Array[Byte](1, 2, 3), mono16, 24000).map(_._1.length) shouldBe Right(2)
    resample(Array.fill[Byte](7)(1), mono16.copy(numChannels = 2), 24000).map(_._1.length) shouldBe Right(4)
  }

  it should "produce exactly round(frames * target / source) frames, for every mix of rates and lengths" in {
    val rates  = List((24000, 16000), (16000, 24000), (44100, 16000), (8000, 48000), (48000, 8000), (22050, 44100))
    val frames = List(1, 2, 3, 7, 100, 441, 1000, 2400, 4801)
    for {
      (from, to) <- rates
      n          <- frames
    } {
      val expected = math.round(n.toDouble * to / from).toInt
      val result   = resample(sine(440, from, n), AudioMeta(from, 1, 16), to)
      withClue(s"$n frames at $from Hz to $to Hz: ")(result.map(_._1.length / 2) shouldBe Right(expected))
    }
  }

  it should "count whole frames of every channel" in {
    val stereo = AudioMeta(24000, 2, 16)
    val result = resample(sine(440, 24000, 2400, channels = 2), stereo, 16000)
    result.map(_._1.length) shouldBe Right(1600 * 2 * 2)
    result.map(_._2) shouldBe Right(stereo.copy(sampleRate = 16000))
  }

  it should "report the new sample rate and leave the other metadata alone" in {
    resample(audio, mono16, 16000).map(_._2) shouldBe Right(AudioMeta(16000, 1, 16))
  }

  it should "return the input as it is when the rate already matches" in {
    resample(audio, mono16, 24000).map(_._1.toSeq) shouldBe Right(audio.toSeq)
  }

  it should "keep a 440 Hz sine's frequency, length and level from 24 kHz to 16 kHz" in {
    val (out, meta) = resample(audio, mono16, 16000).toOption.getOrElse(fail("resampling failed"))
    meta.sampleRate shouldBe 16000
    out.length / 2 shouldBe 1600
    // 0.1 s of 440 Hz is 44 cycles, so 88 zero crossings
    zeroCrossings(out) should ((be >= 86).and(be <= 90))
    rms(out) shouldBe (rms(audio) +- rms(audio) * 0.1)
  }

  it should "keep a 440 Hz sine's frequency, length and level from 16 kHz up to 48 kHz" in {
    val source      = sine(440, 16000, 1600)
    val (out, meta) = resample(source, AudioMeta(16000, 1, 16), 48000).toOption.getOrElse(fail("resampling failed"))
    meta.sampleRate shouldBe 48000
    out.length / 2 shouldBe 4800
    zeroCrossings(out) should ((be >= 86).and(be <= 90))
    rms(out) shouldBe (rms(source) +- rms(source) * 0.1)
  }

  it should "delay the signal by no more than a few frames" in {
    // the lag, in output frames, at which the output best matches an ideal sine at the new rate
    def lag(from: Int, to: Int): Int = {
      val source = sine(440, from, from / 10)
      val out   = samples(resample(source, AudioMeta(from, 1, 16), to).toOption.getOrElse(fail("resampling failed"))._1)
      val ideal = samples(sine(440, to, out.size))
      val window = out.size / 10 until out.size - out.size / 10
      (-8 to 8).maxBy(shift => window.map(i => out(i).toDouble * ideal(i - shift)).sum)
    }
    // 440 Hz is about 36 frames a period at 16 kHz, so a search of +-8 cannot alias; 1 to 8 is a delay, 0 is none
    List((24000, 16000), (16000, 48000), (44100, 16000)).foreach { case (from, to) =>
      withClue(s"$from Hz to $to Hz: ")(lag(from, to) should ((be >= 0).and(be <= 8)))
    }
  }

  it should "finish for every well-formed format, with the exact length or a ProcessingError" in {
    for {
      depth    <- List(8, 16, 24, 32)
      channels <- List(1, 2, 6, 64)
    } {
      val meta   = AudioMeta(24000, channels, depth)
      val frames = 240
      val result = resample(new Array[Byte](frames * channels * (depth / 8)), meta, 16000)
      withClue(s"$depth-bit, $channels channels: ") {
        result match {
          case Right((out, _)) => out.length shouldBe 160 * channels * (depth / 8)
          case Left(error)     => error shouldBe a[org.llm4s.error.ProcessingError]
        }
      }
    }
  }

  // --- the pipeline built on it ----------------------------------------------------------------------------------

  "standardizeForSTT" should "return empty audio for empty input" in {
    timed()(AudioPreprocessing.standardizeForSTT(Array.emptyByteArray, mono16)).map(_._1.length) shouldBe Right(0)
  }

  it should "pass an invalid target rate through as a ValidationError" in {
    validationField(
      timed()(AudioPreprocessing.standardizeForSTT(audio, mono16, targetRate = -8000))
    ) shouldBe "targetRate"
  }

  "ResampleConverter" should "report an invalid target rate as a ValidationError" in {
    val converter = AudioConverter.ResampleConverter(0)
    validationField(timed()(converter.convert((audio, mono16)))) shouldBe "targetRate"
  }
}
