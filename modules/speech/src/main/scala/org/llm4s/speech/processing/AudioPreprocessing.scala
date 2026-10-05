package org.llm4s.speech.processing

import org.llm4s.error.{ ProcessingError, ValidationError }
import org.llm4s.types.Result
import org.llm4s.speech.{ AudioFormat, AudioMeta, GeneratedAudio }
import org.llm4s.speech.io.BinaryReader
import BinaryReader._

import java.io.{ ByteArrayInputStream, ByteArrayOutputStream, IOException }
import javax.sound.sampled.{
  AudioInputStream,
  AudioSystem,
  LineUnavailableException,
  UnsupportedAudioFileException,
  AudioFormat => JAudioFormat
}
import scala.annotation.tailrec
import scala.util.{ Try, Using }

/**
 * Functional audio preprocessing utilities.
 * These are pure transformations described as functions that return either errors or processed audio.
 */
object AudioPreprocessing {

  /** Lowest and highest sample rate, in Hz, that [[resamplePcm16]] accepts for the source and for the target. */
  private[speech] val MinSampleRate = 1
  private[speech] val MaxSampleRate = 768000

  /** Most channels [[resamplePcm16]] accepts. */
  private[speech] val MaxChannels = 64

  /** The largest byte array the JVM can reliably allocate. */
  private val MaxArrayBytes = Int.MaxValue - 8L

  /**
   * Resample PCM little-endian bytes to `targetRate` using Java Sound.
   *
   * The contract:
   *  - '''Arguments are checked first.''' The target rate and the source's sample rate must be between
   *    [[MinSampleRate]] and [[MaxSampleRate]] Hz, the channel count between 1 and [[MaxChannels]], and the bit depth a
   *    multiple of 8 from 8 to 32. Anything else is a `Left(ValidationError)` naming the field (`targetRate`,
   *    `source.sampleRate`, `source.numChannels` or `source.bitDepth`), and nothing reaches Java Sound.
   *  - '''The output has exactly `round(frames * targetRate / sourceRate)` frames''' (half up), where `frames` is the
   *    number of whole frames in `bytes`. A trailing partial frame is ignored, as it is by [[toMono]] and
   *    [[trimSilence]]. Java Sound's converter delays the signal by its filter latency and appends the same number of
   *    frames as a tail; the length fit drops that tail, so the output is the source delayed by a fraction of a
   *    millisecond (at most 0.3 ms for 24 to 16, 16 to 24, 44.1 to 16, 16 to 48, 8 to 48, 48 to 8 and 22.05 to 44.1
   *    kHz, measured on JDK 21) and the source's final fraction of a millisecond is not in it.
   *  - '''Empty input gives empty output''', and so does input too short to make a single output frame.
   *  - '''Equal rates return a copy of the whole frames''' without converting.
   *  - '''An output too large for a byte array is a `Left(ValidationError)` on `targetRate`''', checked before anything
   *    is allocated.
   *  - '''It cannot loop without making progress:''' reading stops at the end of the stream, at a read that returns no
   *    bytes, and once the expected number of bytes has been read, whichever comes first.
   *
   * @param bytes signed little-endian PCM
   * @param source the sample rate, channel count and bit depth of `bytes`
   * @param targetRate the rate to convert to, in Hz
   */
  def resamplePcm16(bytes: Array[Byte], source: AudioMeta, targetRate: Int): Result[(Array[Byte], AudioMeta)] =
    for {
      frameSize <- validated(source, targetRate)
      inFrames  = bytes.length / frameSize
      outFrames = expectedFrames(inFrames, source.sampleRate, targetRate)
      outBytes <- outputSize(outFrames, frameSize, targetRate)
      resampled <-
        if (outFrames == 0) Right(Array.emptyByteArray)
        else if (source.sampleRate == targetRate) Right(java.util.Arrays.copyOf(bytes, outBytes))
        else convert(bytes, inFrames, source, targetRate, outBytes)
    } yield resampled -> source.copy(sampleRate = targetRate)

  /** The frame size, in bytes, when the arguments are usable. */
  private def validated(source: AudioMeta, targetRate: Int): Result[Int] = {
    def rateProblem(field: String, rate: Int): Option[ValidationError] =
      Option.when(rate < MinSampleRate || rate > MaxSampleRate)(
        ValidationError(field, s"must be between $MinSampleRate and $MaxSampleRate Hz, was $rate")
      )
    val problems = List(
      rateProblem("targetRate", targetRate),
      rateProblem("source.sampleRate", source.sampleRate),
      Option.when(source.numChannels < 1 || source.numChannels > MaxChannels)(
        ValidationError("source.numChannels", s"must be between 1 and $MaxChannels, was ${source.numChannels}")
      ),
      Option.when(source.bitDepth < 8 || source.bitDepth > 32 || source.bitDepth % 8 != 0)(
        ValidationError("source.bitDepth", s"must be a multiple of 8 from 8 to 32, was ${source.bitDepth}")
      )
    ).flatten
    problems.headOption.toLeft(source.numChannels * (source.bitDepth / 8))
  }

  /** `round(inFrames * targetRate / sourceRate)`, half up, exact: both rates are at most [[MaxSampleRate]]. */
  private[processing] def expectedFrames(inFrames: Long, sourceRate: Int, targetRate: Int): Long =
    (inFrames * targetRate * 2 + sourceRate) / (2L * sourceRate)

  private def outputSize(outFrames: Long, frameSize: Int, targetRate: Int): Result[Int] = {
    val bytes = outFrames * frameSize
    if (bytes > MaxArrayBytes)
      Left(
        ValidationError("targetRate", s"a resampled output of $bytes bytes at $targetRate Hz would not fit in an array")
      )
    else Right(bytes.toInt)
  }

  /** Runs the conversion in Java Sound and returns exactly `outBytes` bytes of it. */
  private def convert(
    bytes: Array[Byte],
    inFrames: Int,
    source: AudioMeta,
    targetRate: Int,
    outBytes: Int
  ): Result[Array[Byte]] = {
    val attempt = Try {
      val srcFormat = new JAudioFormat(source.sampleRate.toFloat, source.bitDepth, source.numChannels, true, false)
      // the stream's length is `inFrames`, so a trailing partial frame in `bytes` is never read
      val srcAis    = new AudioInputStream(new ByteArrayInputStream(bytes), srcFormat, inFrames)
      val dstFormat = new JAudioFormat(targetRate.toFloat, source.bitDepth, source.numChannels, true, false)
      Using.resource(AudioSystem.getAudioInputStream(dstFormat, srcAis)) { converted =>
        // Java Sound pads its output by a few frames; stopping at outBytes drops the padding, and a short read is
        // padded with silence so the length is always the contract's.
        java.util.Arrays.copyOf(drain(converted.read(_), outBytes), outBytes)
      }
    }
    attempt.toEither.left.map {
      case ex: UnsupportedAudioFileException =>
        ProcessingError.audioResample(
          s"Unsupported audio format: ${source.bitDepth}-bit, ${source.numChannels} channels",
          Some(ex)
        )
      case ex: LineUnavailableException => ProcessingError.audioResample("Audio line unavailable", Some(ex))
      case ex: IOException              => ProcessingError.audioResample("IO error during resampling", Some(ex))
      case ex: IllegalArgumentException =>
        ProcessingError.audioResample(s"Invalid audio parameters: rate=$targetRate", Some(ex))
      case ex: Exception => ProcessingError.audioResample("Resample operation failed", Some(ex))
    }
  }

  /**
   * Reads until the end of the stream (`-1`), a read that returns nothing, or `maxBytes`, whichever comes first.
   * Every pass either stops or stores at least one byte, so it ends after at most `maxBytes` passes.
   */
  private[processing] def drain(read: Array[Byte] => Int, maxBytes: Int): Array[Byte] = {
    val out = new ByteArrayOutputStream(math.min(maxBytes, 1 << 16))
    val buf = new Array[Byte](8192)
    @tailrec def loop(): Unit =
      if (out.size < maxBytes) {
        val n = read(buf)
        if (n > 0) {
          out.write(buf, 0, math.min(n, maxBytes - out.size))
          loop()
        }
      }
    loop()
    out.toByteArray
  }

  /** Convert to mono by averaging channels (PCM16 little-endian). */
  def toMono(bytes: Array[Byte], meta: AudioMeta): Result[(Array[Byte], AudioMeta)] =
    if (meta.numChannels <= 1) Right((bytes, meta))
    else {
      val frameSize     = (meta.bitDepth / 8) * meta.numChannels
      val numFrames     = bytes.length / frameSize
      val monoFrameSize = meta.bitDepth / 8
      val out           = new Array[Byte](numFrames * monoFrameSize)

      (0 until numFrames).foreach { frameIndex =>
        val sum = (0 until meta.numChannels).foldLeft(0) { (acc, ch) =>
          val base = frameIndex * frameSize + ch * (meta.bitDepth / 8)
          // Use implicit binary reader for cleaner code
          val (sample, _) = bytes.read[Short](base)
          acc + sample.toInt
        }

        val avg: Short   = (sum / meta.numChannels).toShort
        val outByteIndex = frameIndex * 2

        // Write avg as little-endian short (low byte first)
        out(outByteIndex) = (avg & 0xff).toByte
        out(outByteIndex + 1) = ((avg >> 8) & 0xff).toByte
      }

      Right(out -> meta.copy(numChannels = 1))
    }

  /** Trim leading and trailing silence using a simple amplitude threshold on PCM16. */
  def trimSilence(bytes: Array[Byte], meta: AudioMeta, threshold: Int = 512): Result[(Array[Byte], AudioMeta)] = {
    val attempt = Try {
      val sampleSize = meta.bitDepth / 8
      val frameSize  = sampleSize * meta.numChannels
      val numFrames  = bytes.length / frameSize
      def frameLoud(frameIdx: Int): Boolean = {
        val maxAmplitude = (0 until meta.numChannels).foldLeft(0) { (max, ch) =>
          val base        = frameIdx * frameSize + ch * sampleSize
          val (sample, _) = bytes.read[Short](base)
          math.max(max, math.abs(sample.toInt))
        }
        maxAmplitude >= threshold
      }
      val start    = Iterator.from(0).take(numFrames).find(frameLoud).getOrElse(numFrames)
      val end      = Iterator.iterate(numFrames - 1)(_ - 1).takeWhile(_ >= start).find(frameLoud).getOrElse(start - 1)
      val outStart = start * frameSize
      val outEnd   = (end + 1) * frameSize
      val sliced =
        if (outEnd > outStart) java.util.Arrays.copyOfRange(bytes, outStart, outEnd) else Array.emptyByteArray
      sliced -> meta
    }
    attempt.toEither.left.map {
      case ex: ArrayIndexOutOfBoundsException =>
        ProcessingError.audioTrimming(
          s"Invalid audio data size: expected multiple of ${(meta.bitDepth / 8) * meta.numChannels} bytes",
          Some(ex)
        )
      case ex: IllegalArgumentException =>
        ProcessingError.audioTrimming(s"Invalid threshold value: $threshold", Some(ex))
      case ex: Exception => ProcessingError.audioTrimming("Silence trimming failed", Some(ex))
    }
  }

  /** Compose multiple steps functionally */
  def standardizeForSTT(
    bytes: Array[Byte],
    meta: AudioMeta,
    targetRate: Int = 16000
  ): Result[(Array[Byte], AudioMeta)] =
    for {
      mono       <- toMono(bytes, meta)
      resampled  <- resamplePcm16(mono._1, mono._2, targetRate)
      normalized <- trimSilence(resampled._1, resampled._2)
    } yield normalized

  def wrap(bytes: Array[Byte], meta: AudioMeta, format: AudioFormat = AudioFormat.WavPcm16): GeneratedAudio =
    GeneratedAudio(bytes, meta, format)
}
