package org.llm4s.speech

import org.llm4s.speech.io.WavFileGenerator
import org.llm4s.speech.processing.AudioPreprocessing
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.{ ByteBuffer, ByteOrder }
import java.nio.file.Files

/**
 * What happens to the cloud clients' raw PCM next: written as a playable WAV (the documented
 * path, `WavFileGenerator.saveAsWav`) and resampled for a 16 kHz recogniser.
 */
class CloudAudioPipelineSpec extends AnyFlatSpec with Matchers {

  private def pcm16(samples: Seq[Int]): Array[Byte] = {
    val bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN)
    samples.foreach(s => bb.putShort(s.toShort))
    bb.array()
  }

  private def samplesOf(bytes: Array[Byte]): Array[Int] = {
    val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    Array.fill(bytes.length / 2)(bb.getShort.toInt)
  }

  private def sine(rate: Int, hz: Double, seconds: Double, amp: Double): Array[Byte] =
    pcm16((0 until (rate * seconds).toInt).map(n => math.round(amp * math.sin(2 * math.Pi * hz * n / rate)).toInt))

  /** Rising zero crossings per second: the signal's frequency. */
  private def estimateHz(samples: Array[Int], rate: Int): Double = {
    val crossings = samples.sliding(2).count(p => p(0) < 0 && p(1) >= 0)
    crossings.toDouble * rate / samples.length
  }

  private val cloudMeta = AudioMeta(sampleRate = 24000, numChannels = 1, bitDepth = 16)

  // ------------------------------------------------------------ saveAsWav of cloud TTS output

  "saveAsWav of a cloud TTS result" should "write RIFF/WAVE sizes, 24 kHz mono 16-bit fields and the data untouched" in {
    // Even length: a 16-bit stream is whole samples (Java Sound's writer drops a trailing odd byte).
    val data  = StubHttpClient.binaryAudio.dropRight(1)
    val audio = GeneratedAudio(data, cloudMeta, AudioFormat.RawPcm16)
    val path  = Files.createTempFile("llm4s-pipeline-", ".wav")
    try {
      WavFileGenerator.saveAsWav(audio, path).isRight shouldBe true
      val wav = Files.readAllBytes(path)
      val bb  = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
      new String(wav, 0, 4, "US-ASCII") shouldBe "RIFF"
      new String(wav, 8, 4, "US-ASCII") shouldBe "WAVE"
      new String(wav, 12, 4, "US-ASCII") shouldBe "fmt "
      bb.getInt(16) shouldBe 16
      bb.getShort(20).toInt shouldBe 1 // PCM
      bb.getShort(22).toInt shouldBe 1 // mono
      bb.getInt(24) shouldBe 24000
      bb.getInt(28) shouldBe 48000     // byte rate
      bb.getShort(32).toInt shouldBe 2 // block align
      bb.getShort(34).toInt shouldBe 16
      new String(wav, 36, 4, "US-ASCII") shouldBe "data"
      bb.getInt(40) shouldBe data.length
      bb.getInt(4) shouldBe wav.length - 8
      wav.drop(44) shouldBe data
    } finally Files.deleteIfExists(path)
  }

  it should "round-trip through readWavFile with the metadata of the service" in {
    val data = sine(24000, 300.0, 0.1, 8000)
    val path = Files.createTempFile("llm4s-pipeline-", ".wav")
    try {
      WavFileGenerator.saveAsWav(GeneratedAudio(data, cloudMeta, AudioFormat.RawPcm16), path).isRight shouldBe true
      val back = WavFileGenerator.readWavFile(path).toOption.get
      back.meta shouldBe cloudMeta
      back.data shouldBe data
    } finally Files.deleteIfExists(path)
  }

  // ------------------------------------------------------------ resampling 24 kHz -> 16 kHz

  "resamplePcm16 from 24 kHz to 16 kHz" should "keep a 440 Hz sine's frequency and length within a sample" in {
    val (out, meta) = AudioPreprocessing.resamplePcm16(sine(24000, 440.0, 1.0, 12000), cloudMeta, 16000).toOption.get
    meta.sampleRate shouldBe 16000
    val samples = samplesOf(out)
    math.abs(samples.length - 16000) should be <= 2 // Java Sound is off by up to 2 samples
    math.abs(estimateHz(samples, 16000) - 440.0) should be <= 2.0
  }

  it should "keep the amplitude of the sine (no gross attenuation or gain)" in {
    val out =
      samplesOf(AudioPreprocessing.resamplePcm16(sine(24000, 440.0, 0.5, 12000), cloudMeta, 16000).toOption.get._1)
    val peak = out.map(math.abs).max
    peak should ((be >= 11000).and(be <= 13000))
  }

  it should "turn silence into silence" in {
    val out = AudioPreprocessing.resamplePcm16(new Array[Byte](48000), cloudMeta, 16000).toOption.get._1
    out.forall(_ == 0) shouldBe true
    math.abs(out.length - 32000) should be <= 4 // bytes: Java Sound is off by up to 2 samples
  }

  it should "accept empty input (Java Sound pads it to a couple of silent samples)" in {
    val result = AudioPreprocessing.resamplePcm16(Array.emptyByteArray, cloudMeta, 16000)
    result.toOption.get._1.length should be <= 4
    result.toOption.get._1.forall(_ == 0) shouldBe true
  }

  it should "not fail on a single sample" in {
    val result = AudioPreprocessing.resamplePcm16(pcm16(Seq(1234)), cloudMeta, 16000)
    result.isRight shouldBe true
    result.toOption.get._1.length should be <= 4
  }

  it should "not wrap around on a full-scale signal (clipping, not overflow)" in {
    val out =
      samplesOf(AudioPreprocessing.resamplePcm16(sine(24000, 440.0, 0.5, 32767), cloudMeta, 16000).toOption.get._1)
    // A wrapped sample would jump by about 65536; a clean 440 Hz sine at 16 kHz steps by at most ~5700.
    out.sliding(2).map(p => math.abs(p(1) - p(0))).max should be < 7000
    out.map(math.abs).max should be >= 32000
  }

}
