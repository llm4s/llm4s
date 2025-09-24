package org.llm4s.samples

import org.scalamock.scalatest.MockFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.{ ByteArrayOutputStream, DataOutputStream }
import java.nio.{ ByteBuffer, ByteOrder }
import scala.util.{ Failure, Success }

class RichDataOutputStreamSpec extends AnyFlatSpec with Matchers with MockFactory {

  // Helper methods to create test instances
  private def createTestStream(): (ByteArrayOutputStream, DataOutputStream, SpeechSamples.RichDataOutputStream) = {
    val baos    = new ByteArrayOutputStream()
    val dos     = new DataOutputStream(baos)
    val richDos = new SpeechSamples.RichDataOutputStream(dos)
    (baos, dos, richDos)
  }

  private def getWrittenBytes(baos: ByteArrayOutputStream): Array[Byte] = baos.toByteArray

  // Helper to convert bytes to little-endian int for verification
  private def bytesToLittleEndianInt(bytes: Array[Byte], offset: Int = 0): Int =
    ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt

  // Helper to convert bytes to little-endian short for verification
  private def bytesToLittleEndianShort(bytes: Array[Byte], offset: Int = 0): Short =
    ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).getShort

  behavior.of("RichDataOutputStream.writeIt(String)")

  it should "successfully write a string to the stream" in {
    val (baos, dos, richDos) = createTestStream()

    val result = richDos.writeString("RIFF")

    result shouldBe a[Success[_]]
    getWrittenBytes(baos) should equal("RIFF".getBytes)
    dos.close()
  }

  it should "write an empty string successfully" in {
    val (baos, dos, richDos) = createTestStream()

    val result = richDos.writeString("")

    result shouldBe a[Success[_]]
    getWrittenBytes(baos) should equal(Array.empty[Byte])
    dos.close()
  }

  it should "handle special characters in strings" in {
    val (baos, dos, richDos) = createTestStream()
    val testString           = "fmt " // Note the space character

    val result = richDos.writeString(testString)

    result shouldBe a[Success[_]]
    getWrittenBytes(baos) should equal(testString.getBytes)
    dos.close()
  }

  behavior.of("RichDataOutputStream.writeIt(Int)")

  it should "write an integer in little-endian format" in {
    val (baos, dos, richDos) = createTestStream()
    val testInt              = 0x12345678

    val result = richDos.writeInt(testInt)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(4)
    bytesToLittleEndianInt(bytes) should equal(testInt)
    dos.close()
  }

  it should "write zero correctly" in {
    val (baos, dos, richDos) = createTestStream()

    val result = richDos.writeInt(0)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(4)
    bytesToLittleEndianInt(bytes) should equal(0)
    dos.close()
  }

  it should "write negative integers correctly" in {
    val (baos, dos, richDos) = createTestStream()
    val testInt              = -1

    val result = richDos.writeInt(testInt)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(4)
    bytesToLittleEndianInt(bytes) should equal(testInt)
    dos.close()
  }

  it should "write maximum integer value" in {
    val (baos, dos, richDos) = createTestStream()
    val testInt              = Int.MaxValue

    val result = richDos.writeInt(testInt)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytesToLittleEndianInt(bytes) should equal(testInt)
    dos.close()
  }

  behavior.of("RichDataOutputStream.writeIt(Short)")

  it should "write a short in little-endian format" in {
    val (baos, dos, richDos) = createTestStream()
    val testShort: Short     = 0x1234.toShort

    val result = richDos.writeShort(testShort)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(2)
    bytesToLittleEndianShort(bytes) should equal(testShort)
    dos.close()
  }

  it should "write converted integers as shorts" in {
    val (baos, dos, richDos) = createTestStream()

    val result = richDos.writeShort(1.toShort)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(2)
    bytesToLittleEndianShort(bytes) should equal(1)
    dos.close()
  }

  it should "handle short overflow correctly" in {
    val (baos, dos, richDos) = createTestStream()
    val testShort: Short     = Short.MaxValue

    val result = richDos.writeShort(testShort)

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytesToLittleEndianShort(bytes) should equal(testShort)
    dos.close()
  }

  behavior.of("RichDataOutputStream.writeZeros")

  it should "write zeros for a given range" in {
    val (baos, dos, richDos) = createTestStream()
    val result               = richDos.writeListOfValues(LazyList.continually(0).take(3))

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(6) // 3 shorts * 2 bytes each

    // Verify all bytes are zero
    bytes.foreach(_ should equal(0))
    dos.close()
  }

  it should "write nothing for an empty range" in {
    val (baos, dos, richDos) = createTestStream()
    val result               = richDos.writeListOfValues(LazyList.continually(0).take(0))

    result shouldBe a[Success[_]]
    getWrittenBytes(baos) should equal(Array.empty[Byte])
    dos.close()
  }

  it should "write correct number of zeros for large ranges" in {
    val (baos, dos, richDos) = createTestStream()
    val result               = richDos.writeListOfValues(LazyList.continually(0).take(1000))

    result shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)
    bytes.length should equal(2000) // 1000 shorts * 2 bytes each
    bytes.foreach(_ should equal(0))
    dos.close()
  }

  it should "handle negative ranges correctly" in {
    val (baos, dos, richDos) = createTestStream()
    val result               = richDos.writeListOfValues(LazyList.empty[Int])

    result shouldBe a[Success[_]]
    getWrittenBytes(baos) should equal(Array.empty[Byte])
    dos.close()
  }

  behavior.of("RichDataOutputStream error handling")

  behavior.of("RichDataOutputStream combined operations")

  it should "successfully chain multiple write operations" in {
    val (baos, dos, richDos) = createTestStream()

    val results = for {
      _ <- richDos.writeString("RIFF")
      _ <- richDos.writeInt(1234)
      _ <- richDos.writeShort(16.toShort)
      _ <- richDos.writeListOfValues(LazyList.continually(0).take(2))
    } yield ()

    results shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)

    // Verify the structure
    bytes.slice(0, 4) should equal("RIFF".getBytes)
    bytesToLittleEndianInt(bytes, 4) should equal(1234)
    bytesToLittleEndianShort(bytes, 8) should equal(16)
    // Last 4 bytes should be zeros
    bytes.slice(10, 14).foreach(_ should equal(0))

    dos.close()
  }

  it should "stop chain execution on first failure" in {
    val box = new ByteArrayOutputStream() {
      override def write(b: Int): Unit =
        throw new RuntimeException("Something Broke")
    }
    val dos     = new DataOutputStream(box)
    val richDos = new SpeechSamples.RichDataOutputStream(dos)
    val results = for {
      _ <- richDos.writeString("RIFF")    // This will fail
      _ <- richDos.writeInt(1234)         // This won't execute
      _ <- richDos.writeShort(16.toShort) // This won't execute
    } yield ()

    results shouldBe a[Failure[_]]
    results.failed.get.getMessage shouldBe "Something Broke"
    val bytes = getWrittenBytes(box)
    bytes.length should equal(0)
  }

  behavior.of("RichDataOutputStream with real WAV data")

  it should "write a complete WAV header structure" in {
    val (baos, dos, richDos) = createTestStream()

    // Simulate WAV header data
    val sampleRate     = 8000
    val channels       = 1
    val bitsPerSample  = 16
    val bytesPerSample = bitsPerSample / 8
    val blockAlign     = channels * bytesPerSample
    val byteRate       = sampleRate * blockAlign
    val dataSize       = sampleRate * channels * bytesPerSample
    val fileSize       = 36 + dataSize

    val results = for {
      _ <- richDos.writeString("RIFF")
      _ <- richDos.writeInt(fileSize)
      _ <- richDos.writeString("WAVE")
      _ <- richDos.writeString("fmt ")
      _ <- richDos.writeInt(16)
      _ <- richDos.writeShort(1.toShort)
      _ <- richDos.writeShort(channels.toShort)
      _ <- richDos.writeInt(sampleRate)
      _ <- richDos.writeInt(byteRate)
      _ <- richDos.writeShort(blockAlign.toShort)
      _ <- richDos.writeShort(bitsPerSample.toShort)
      _ <- richDos.writeString("data")
      _ <- richDos.writeInt(dataSize)
    } yield ()

    results shouldBe a[Success[_]]
    val bytes = getWrittenBytes(baos)

    // Verify key parts of the header
    bytes.slice(0, 4) should equal("RIFF".getBytes)
    bytes.slice(8, 12) should equal("WAVE".getBytes)
    bytes.slice(12, 16) should equal("fmt ".getBytes)
    bytes.slice(36, 40) should equal("data".getBytes)

    // Verify numeric values
    bytesToLittleEndianInt(bytes, 4) should equal(fileSize)
    bytesToLittleEndianInt(bytes, 16) should equal(16)  // fmt chunk size
    bytesToLittleEndianShort(bytes, 20) should equal(1) // PCM format
    bytesToLittleEndianInt(bytes, 24) should equal(sampleRate)

    dos.close()
  }
}
