package org.llm4s.speech.io

import org.llm4s.speech.AudioMeta
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WavFileGeneratorSpec extends AnyFlatSpec with Matchers {
  import BinaryReader._

  val testMeta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
  val dataSize = 1000

  "createWavHeader" should "produce a 44-byte result" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach(header => header.length shouldBe 44)
  }

  it should "start with RIFF magic bytes" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach(header => new String(header.slice(0, 4)) shouldBe "RIFF")
  }

  it should "have WAVE marker at bytes 8-11" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach(header => new String(header.slice(8, 12)) shouldBe "WAVE")
  }

  it should "correctly encode NumChannels at offset 22" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (numChannels, _) = header.read[Short](22)
      numChannels shouldBe testMeta.numChannels.toShort
    }
  }

  it should "correctly encode SampleRate at offset 24" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (sampleRate, _) = header.read[Int](24)
      sampleRate shouldBe testMeta.sampleRate
    }
  }

  it should "correctly encode BitsPerSample at offset 34" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (bitsPerSample, _) = header.read[Short](34)
      bitsPerSample shouldBe testMeta.bitDepth.toShort
    }
  }

  it should "encode ChunkSize as dataSize + 36 at offset 4" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (chunkSize, _) = header.read[Int](4)
      chunkSize shouldBe dataSize + 36
    }
  }

  it should "have the fmt subchunk marker at offset 12" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach(header => new String(header.slice(12, 16)) shouldBe "fmt ")
  }

  it should "have the data subchunk marker at offset 36" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach(header => new String(header.slice(36, 40)) shouldBe "data")
  }

  it should "encode the data chunk size at offset 40" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (chunkSize, _) = header.read[Int](40)
      chunkSize shouldBe dataSize
    }
  }

  it should "encode the byte rate at offset 28" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (byteRate, _) = header.read[Int](28)
      byteRate shouldBe testMeta.sampleRate * testMeta.numChannels * (testMeta.bitDepth / 8)
    }
  }

  it should "encode the block align at offset 32" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (blockAlign, _) = header.read[Short](32)
      blockAlign shouldBe (testMeta.numChannels * testMeta.bitDepth / 8).toShort
    }
  }

  it should "handle large data sizes" in {
    val largeSize = 10 * 1024 * 1024 // 10MB
    val result    = WavFileGenerator.createWavHeader(largeSize, testMeta)
    result.isRight shouldBe true
    result.foreach { header =>
      val (chunkSize, _) = header.read[Int](4)
      chunkSize shouldBe largeSize + 36
    }
  }

  it should "reject invalid metadata" in {
    val result = WavFileGenerator.createWavHeader(dataSize, testMeta.copy(bitDepth = 12))
    result.isLeft shouldBe true
  }

  "validateMetadata" should "accept valid mono PCM audio" in {
    val result = WavFileGenerator.validateMetadata(AudioMeta(16000, 1, 16))
    result shouldBe Right(AudioMeta(16000, 1, 16))
  }

  it should "accept all supported bit depths" in {
    WavFileGenerator.SupportedBitDepths.foreach { bitDepth =>
      val meta = AudioMeta(16000, 1, bitDepth)
      WavFileGenerator.validateMetadata(meta) shouldBe Right(meta)
    }
  }

  it should "reject an unsupported bit depth" in {
    val result = WavFileGenerator.validateMetadata(AudioMeta(16000, 1, 12))
    result.isLeft shouldBe true
    result.swap.toOption.map(_.message).exists(_.contains("Bit depth")) shouldBe true
  }

  it should "reject a non-positive sample rate" in {
    val result = WavFileGenerator.validateMetadata(AudioMeta(0, 1, 16))
    result.isLeft shouldBe true
    result.swap.toOption.map(_.message).exists(_.contains("Sample rate")) shouldBe true
  }

  it should "reject zero channels" in {
    val result = WavFileGenerator.validateMetadata(AudioMeta(16000, 0, 16))
    result.isLeft shouldBe true
    result.swap.toOption.map(_.message).exists(_.contains("channels")) shouldBe true
  }

  it should "reject more than the maximum number of channels" in {
    val result = WavFileGenerator.validateMetadata(AudioMeta(16000, WavFileGenerator.MaxChannels + 1, 16))
    result.isLeft shouldBe true
    result.swap.toOption.map(_.message).exists(_.contains("channels")) shouldBe true
  }

  "writeToTempWav + readWavFile" should "roundtrip AudioMeta correctly" in {
    // Stereo 16-bit PCM, small size for fast test
    val sampleCount = 1024
    val pcmData     = Array.fill[Byte](sampleCount * 2 * 2)(0) // stereo * 2 bytes/sample
    val meta        = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)

    val result = for {
      path  <- WavFileGenerator.writeToTempWav(pcmData, meta, "test-roundtrip")
      audio <- WavFileGenerator.readWavFile(path)
      _ = scala.util.Try(java.nio.file.Files.deleteIfExists(path))
    } yield audio

    result.isRight shouldBe true
    val audio = result.toOption.get
    audio.meta.sampleRate shouldBe 44100
    audio.meta.numChannels shouldBe 2
    audio.meta.bitDepth shouldBe 16
  }
}
