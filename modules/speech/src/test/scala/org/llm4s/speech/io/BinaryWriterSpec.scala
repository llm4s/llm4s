package org.llm4s.speech.io

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import java.io.{ ByteArrayOutputStream, DataOutputStream }

class BinaryWriterSpec extends AnyFlatSpec with Matchers {
  import BinaryReader._

  // Use DataOutputStreamOps directly to avoid naming conflict with DataOutputStream.write
  private def makeOps(): (ByteArrayOutputStream, BinaryWriter.DataOutputStreamOps) = {
    val bos = new ByteArrayOutputStream()
    (bos, new BinaryWriter.DataOutputStreamOps(new DataOutputStream(bos)))
  }

  "shortWriter" should "write 2 bytes in little-endian order" in {
    val (bos, dos) = makeOps()
    dos.write[Short](0x1234.toShort)
    val bytes = bos.toByteArray
    bytes.length shouldBe 2
    bytes(0) shouldBe 0x34.toByte // low byte first (little-endian)
    bytes(1) shouldBe 0x12.toByte
  }

  "intWriter" should "write 4 bytes in little-endian order" in {
    val (bos, dos) = makeOps()
    dos.write[Int](0x12345678)
    val bytes = bos.toByteArray
    bytes.length shouldBe 4
    bytes(0) shouldBe 0x78.toByte
    bytes(1) shouldBe 0x56.toByte
    bytes(2) shouldBe 0x34.toByte
    bytes(3) shouldBe 0x12.toByte
  }

  "BinaryWriter/BinaryReader" should "roundtrip Short values" in {
    val (bos, dos)      = makeOps()
    val original: Short = 12345
    dos.write[Short](original)
    val bytes          = bos.toByteArray
    val (result, next) = bytes.read[Short](0)
    result shouldBe original
    next shouldBe 2
  }

  it should "roundtrip Int values" in {
    val (bos, dos) = makeOps()
    val original   = 987654321
    dos.write[Int](original)
    val bytes          = bos.toByteArray
    val (result, next) = bytes.read[Int](0)
    result shouldBe original
    next shouldBe 4
  }

  it should "roundtrip boundary values for Short" in {
    for (value <- Seq[Short](0, Short.MaxValue, Short.MinValue, -1)) {
      val (bos, dos) = makeOps()
      dos.write[Short](value)
      val bytes       = bos.toByteArray
      val (result, _) = bytes.read[Short](0)
      result shouldBe value
    }
  }

  it should "roundtrip boundary values for Int" in {
    for (value <- Seq(0, Int.MaxValue, Int.MinValue, -1)) {
      val (bos, dos) = makeOps()
      dos.write[Int](value)
      val bytes       = bos.toByteArray
      val (result, _) = bytes.read[Int](0)
      result shouldBe value
    }
  }

  "shortReader" should "throw on insufficient bytes" in {
    val bytes = Array[Byte](0x01)
    an[ArrayIndexOutOfBoundsException] should be thrownBy {
      bytes.read[Short](0)
    }
  }

  it should "throw when offset + 2 exceeds array length" in {
    val bytes = Array[Byte](0x01, 0x02, 0x03)
    an[ArrayIndexOutOfBoundsException] should be thrownBy {
      bytes.read[Short](2)
    }
  }

  "intReader" should "throw on insufficient bytes" in {
    val bytes = Array[Byte](0x01, 0x02, 0x03)
    an[ArrayIndexOutOfBoundsException] should be thrownBy {
      bytes.read[Int](0)
    }
  }

  it should "throw when offset + 4 exceeds array length" in {
    val bytes = Array[Byte](0x01, 0x02, 0x03, 0x04, 0x05)
    an[ArrayIndexOutOfBoundsException] should be thrownBy {
      bytes.read[Int](2)
    }
  }
}
