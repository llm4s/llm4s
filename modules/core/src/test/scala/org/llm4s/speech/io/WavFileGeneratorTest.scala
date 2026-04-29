package org.llm4s.speech.io

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.speech.AudioMeta
import org.llm4s.speech.io.WavFileGenerator._
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavFileGeneratorTest extends AnyFunSuite {

  // Valid metadata for testing
  val validMeta = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)

  // Standard WAV file constants
  val BYTES_PER_SAMPLE_16BIT = 2
  val WAV_HEADER_SIZE        = 44

  test("validateMetadata should accept valid mono PCM audio") {
    val result = validateMetadata(validMeta)
    assert(result.isRight)
    assert(result.exists(_.sampleRate == 16000))
  }

  test("validateMetadata should reject invalid bit depth") {
    val invalidMeta = validMeta.copy(bitDepth = 12)
    val result      = validateMetadata(invalidMeta)
    assert(result.isLeft)
    assert(result.left.exists(_.message.contains("Bit depth")))
  }

  test("validateMetadata should accept valid bit depths") {
    for (bitDepth <- List(8, 16, 24, 32)) {
      val meta   = validMeta.copy(bitDepth = bitDepth)
      val result = validateMetadata(meta)
      assert(result.isRight, s"Bit depth $bitDepth should be valid")
    }
  }

  test("validateMetadata should reject zero channels") {
    val invalidMeta = validMeta.copy(numChannels = 0)
    val result      = validateMetadata(invalidMeta)
    assert(result.isLeft)
    assert(result.left.exists(_.message.contains("channels")))
  }

  test("validateMetadata should reject too many channels") {
    val invalidMeta = validMeta.copy(numChannels = 10)
    val result      = validateMetadata(invalidMeta)
    assert(result.isLeft)
    assert(result.left.exists(_.message.contains("channels")))
  }

  test("validateMetadata should accept stereo audio") {
    val stereometa = validMeta.copy(numChannels = 2)
    val result     = validateMetadata(stereometa)
    assert(result.isRight)
    assert(result.exists(_.numChannels == 2))
  }

  test("createWavHeader should create valid 44-byte header") {
    val result = createWavHeader(dataSize = 1000, validMeta)
    assert(result.isRight)
    result.foreach(header => assert(header.length == WAV_HEADER_SIZE))
  }

  test("createWavHeader should start with RIFF marker") {
    val result = createWavHeader(dataSize = 1000, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val riff = new String(header.slice(0, 4), "US-ASCII")
      assert(riff == "RIFF")
    }
  }

  test("createWavHeader should contain WAVE marker") {
    val result = createWavHeader(dataSize = 1000, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val wave = new String(header.slice(8, 12), "US-ASCII")
      assert(wave == "WAVE")
    }
  }

  test("createWavHeader should contain fmt subchunk") {
    val result = createWavHeader(dataSize = 1000, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val fmt = new String(header.slice(12, 16), "US-ASCII")
      assert(fmt == "fmt ")
    }
  }

  test("createWavHeader should contain data subchunk") {
    val result = createWavHeader(dataSize = 1000, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val data = new String(header.slice(36, 40), "US-ASCII")
      assert(data == "data")
    }
  }

  test("createWavHeader should encode correct file size") {
    val dataSize = 2000
    val result   = createWavHeader(dataSize, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val fileSize = buffer.getInt(4)
      assert(fileSize == dataSize + 36)
    }
  }

  test("createWavHeader should encode correct data chunk size") {
    val dataSize = 5000
    val result   = createWavHeader(dataSize, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val chunkSize = buffer.getInt(40)
      assert(chunkSize == dataSize)
    }
  }

  test("createWavHeader should encode correct sample rate") {
    val meta   = validMeta.copy(sampleRate = 44100)
    val result = createWavHeader(1000, meta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val sampleRate = buffer.getInt(24)
      assert(sampleRate == 44100)
    }
  }

  test("createWavHeader should encode correct number of channels") {
    val meta   = validMeta.copy(numChannels = 2)
    val result = createWavHeader(1000, meta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val channels = buffer.getShort(22).toInt
      assert(channels == 2)
    }
  }

  test("createWavHeader should encode correct bit depth") {
    val meta   = validMeta.copy(bitDepth = 24)
    val result = createWavHeader(1000, meta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val bitDepth = buffer.getShort(34).toInt
      assert(bitDepth == 24)
    }
  }

  test("createWavHeader should reject invalid metadata") {
    val invalidMeta = validMeta.copy(bitDepth = 12)
    val result      = createWavHeader(1000, invalidMeta)
    assert(result.isLeft)
  }

  test("createWavHeader should work with stereo audio") {
    val stereoMeta = validMeta.copy(numChannels = 2)
    val result     = createWavHeader(2000, stereoMeta)
    assert(result.isRight)
    result.foreach { header =>
      assert(header.length == WAV_HEADER_SIZE)
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      assert(buffer.getShort(22) == 2.toShort)
    }
  }

  test("createWavHeader should encode correct byte rate") {
    val meta   = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    val result = createWavHeader(1000, meta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val byteRate = buffer.getInt(28)
      val expected = 16000 * 1 * 2 // sampleRate * channels * bytesPerSample
      assert(byteRate == expected)
    }
  }

  test("createWavHeader should encode correct block align for mono") {
    val meta   = AudioMeta(sampleRate = 16000, numChannels = 1, bitDepth = 16)
    val result = createWavHeader(1000, meta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val blockAlign = buffer.getShort(32).toInt
      val expected   = 1 * 2 // channels * bytesPerSample
      assert(blockAlign == expected)
    }
  }

  test("createWavHeader should encode correct block align for stereo") {
    val meta   = AudioMeta(sampleRate = 16000, numChannels = 2, bitDepth = 16)
    val result = createWavHeader(1000, meta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val blockAlign = buffer.getShort(32).toInt
      val expected   = 2 * 2 // channels * bytesPerSample
      assert(blockAlign == expected)
    }
  }

  test("createWavHeader should handle large data sizes") {
    val largeSize = 10 * 1024 * 1024 // 10MB
    val result    = createWavHeader(largeSize, validMeta)
    assert(result.isRight)
    result.foreach { header =>
      val buffer = ByteBuffer.wrap(header)
      buffer.order(ByteOrder.LITTLE_ENDIAN)
      val fileSize = buffer.getInt(4)
      assert(fileSize == largeSize + 36)
    }
  }
}
