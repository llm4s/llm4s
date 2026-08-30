package org.llm4s.speech.processing

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.speech.{ AudioFormat, AudioMeta }

class AudioPreprocessingSpec extends AnyFlatSpec with Matchers {

  // ========== trimSilence ==========

  "trimSilence" should "preserve loud audio" in {
    val meta = AudioMeta(16000, 1, 16)
    // Create PCM16 samples above default threshold (512)
    // Sample value 1000 = 0xE803 little-endian
    val data = Array[Byte](0xe8.toByte, 0x03, 0xe8.toByte, 0x03, 0xe8.toByte, 0x03)

    val result = AudioPreprocessing.trimSilence(data, meta)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe data.length
  }

  it should "trim leading silence" in {
    val meta = AudioMeta(16000, 1, 16)
    // 2 silent frames (value 0) + 1 loud frame (value 1000)
    val data = Array[Byte](0, 0, 0, 0, 0xe8.toByte, 0x03)

    val result = AudioPreprocessing.trimSilence(data, meta)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe 2 // only the loud frame
  }

  it should "trim trailing silence" in {
    val meta = AudioMeta(16000, 1, 16)
    // 1 loud frame + 2 silent frames
    val data = Array[Byte](0xe8.toByte, 0x03, 0, 0, 0, 0)

    val result = AudioPreprocessing.trimSilence(data, meta)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe 2 // only the loud frame
  }

  it should "return empty array for all-silent audio" in {
    val meta = AudioMeta(16000, 1, 16)
    val data = new Array[Byte](10) // all zeros

    val result = AudioPreprocessing.trimSilence(data, meta)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe 0
  }

  it should "handle empty input" in {
    val meta   = AudioMeta(16000, 1, 16)
    val result = AudioPreprocessing.trimSilence(Array.emptyByteArray, meta)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe 0
  }

  it should "respect custom threshold" in {
    val meta = AudioMeta(16000, 1, 16)
    // Sample value 100, below default threshold (512) but above threshold=50
    val data = Array[Byte](100, 0)

    val result = AudioPreprocessing.trimSilence(data, meta, threshold = 50)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe 2
  }

  it should "handle stereo audio" in {
    val meta = AudioMeta(16000, 2, 16)
    // 1 stereo frame: left=0 (silent), right=1000 (loud)
    val data = Array[Byte](0, 0, 0xe8.toByte, 0x03)

    val result = AudioPreprocessing.trimSilence(data, meta)
    result.isRight shouldBe true
    result.toOption.get._1.length shouldBe 4 // frame kept because right channel is loud
  }

  // ========== toMono ==========

  it should "pass through mono audio unchanged" in {
    val meta = AudioMeta(16000, 1, 16)
    val data = Array[Byte](1, 2, 3, 4)

    val result = AudioPreprocessing.toMono(data, meta)
    result shouldBe Right((data, meta))
  }

  // ========== wrap ==========

  "wrap" should "create GeneratedAudio with WavPcm16 format by default" in {
    val meta   = AudioMeta(16000, 1, 16)
    val data   = Array[Byte](1, 2, 3, 4)
    val result = AudioPreprocessing.wrap(data, meta)

    result.data shouldBe data
    result.meta shouldBe meta
    result.format shouldBe AudioFormat.WavPcm16
  }

  it should "create GeneratedAudio with specified format" in {
    val meta   = AudioMeta(16000, 1, 16)
    val data   = Array[Byte](1, 2)
    val result = AudioPreprocessing.wrap(data, meta, AudioFormat.RawPcm16)

    result.format shouldBe AudioFormat.RawPcm16
  }

  // ========== resamplePcm16 ==========

  "resamplePcm16" should "resample mono audio" in {
    val meta = AudioMeta(44100, 1, 16)
    // Create a valid PCM16 buffer (must be aligned to frame size)
    val data = new Array[Byte](882) // 441 frames at 44100Hz

    val result = AudioPreprocessing.resamplePcm16(data, meta, 16000)
    result.isRight shouldBe true
    val (resampled, newMeta) = result.toOption.get
    newMeta.sampleRate shouldBe 16000
    newMeta.numChannels shouldBe 1
    resampled.length should be < data.length
  }

  // ========== standardizeForSTT ==========

  "standardizeForSTT" should "compose mono, resample, and trim" in {
    val meta = AudioMeta(44100, 1, 16)
    // Create valid PCM16 data with some loud samples
    val data = new Array[Byte](882)
    // Write a loud sample in the middle
    data(440) = 0xe8.toByte
    data(441) = 0x03

    val result = AudioPreprocessing.standardizeForSTT(data, meta)
    result.isRight shouldBe true
    result.toOption.get._2.sampleRate shouldBe 16000
  }
}
