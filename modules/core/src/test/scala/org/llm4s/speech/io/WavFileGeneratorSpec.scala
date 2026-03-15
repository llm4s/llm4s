package org.llm4s.speech.io

import org.llm4s.speech.AudioMeta
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class WavFileGeneratorSpec extends AnyFlatSpec with Matchers {
  import BinaryReader._

  val testMeta = AudioMeta(sampleRate = 44100, numChannels = 2, bitDepth = 16)
  val dataSize = 1000

  "createWavHeader" should "produce a 44-byte result" in {
    val header = WavFileGenerator.createWavHeader(dataSize, testMeta)
    header.length shouldBe 44
  }

  it should "start with RIFF magic bytes" in {
    val header = WavFileGenerator.createWavHeader(dataSize, testMeta)
    new String(header.slice(0, 4)) shouldBe "RIFF"
  }

  it should "have WAVE marker at bytes 8-11" in {
    val header = WavFileGenerator.createWavHeader(dataSize, testMeta)
    new String(header.slice(8, 12)) shouldBe "WAVE"
  }

  it should "correctly encode NumChannels at offset 22" in {
    val header           = WavFileGenerator.createWavHeader(dataSize, testMeta)
    val (numChannels, _) = header.read[Short](22)
    numChannels shouldBe testMeta.numChannels.toShort
  }

  it should "correctly encode SampleRate at offset 24" in {
    val header          = WavFileGenerator.createWavHeader(dataSize, testMeta)
    val (sampleRate, _) = header.read[Int](24)
    sampleRate shouldBe testMeta.sampleRate
  }

  it should "correctly encode BitsPerSample at offset 34" in {
    val header             = WavFileGenerator.createWavHeader(dataSize, testMeta)
    val (bitsPerSample, _) = header.read[Short](34)
    bitsPerSample shouldBe testMeta.bitDepth.toShort
  }

  it should "encode ChunkSize as dataSize + 36 at offset 4" in {
    val header         = WavFileGenerator.createWavHeader(dataSize, testMeta)
    val (chunkSize, _) = header.read[Int](4)
    chunkSize shouldBe dataSize + 36
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
