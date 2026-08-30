package org.llm4s.speech.io

import java.io.DataOutputStream

/**
 * Contextual capability for binary writing as suggested in PR review.
 * Provides implicit conversion for different data types to binary format.
 *
 * This abstraction allows writing:
 * writer.write(5.toShort) // writes short
 * writer.write(5) // writes int
 *
 * And enables data-driven programming:
 * List(channels.toShort, sampleRate, byteRate...).foldLeft(dos)(writer.write)
 */
trait BinaryWriter[T] {
  def write(dos: DataOutputStream, value: T): DataOutputStream
}

object BinaryWriter {

  /**
   * Implicit writer for Short values (little endian)
   */
  implicit val shortWriter: BinaryWriter[Short] = new BinaryWriter[Short] {
    def write(dos: DataOutputStream, value: Short): DataOutputStream = {
      dos.writeByte((value & 0xff).toByte)
      dos.writeByte(((value >> 8) & 0xff).toByte)
      dos
    }
  }

  /**
   * Implicit writer for Int values (little endian)
   */
  implicit val intWriter: BinaryWriter[Int] = new BinaryWriter[Int] {
    def write(dos: DataOutputStream, value: Int): DataOutputStream = {
      dos.writeByte((value & 0xff).toByte)
      dos.writeByte(((value >> 8) & 0xff).toByte)
      dos.writeByte(((value >> 16) & 0xff).toByte)
      dos.writeByte(((value >> 24) & 0xff).toByte)
      dos
    }
  }

  /**
   * Implicit writer for Byte values
   */
  implicit val byteWriter: BinaryWriter[Byte] = new BinaryWriter[Byte] {
    def write(dos: DataOutputStream, value: Byte): DataOutputStream = {
      dos.writeByte(value)
      dos
    }
  }

  /**
   * Implicit writer for Array[Byte]
   */
  implicit val byteArrayWriter: BinaryWriter[Array[Byte]] = new BinaryWriter[Array[Byte]] {
    def write(dos: DataOutputStream, value: Array[Byte]): DataOutputStream = {
      dos.write(value)
      dos
    }
  }

  /**
   * Extension methods for DataOutputStream to enable contextual writing
   */
  implicit class DataOutputStreamOps(dos: DataOutputStream) {
    def write[T](value: T)(implicit writer: BinaryWriter[T]): DataOutputStream =
      writer.write(dos, value)

    /**
     * Write multiple values in sequence
     */
    def writeAll[T](values: T*)(implicit writer: BinaryWriter[T]): DataOutputStream =
      values.foldLeft(dos)((stream, value) => writer.write(stream, value))
  }
}

/**
 * Binary reading capabilities for symmetry
 */
trait BinaryReader[T] {
  def read(bytes: Array[Byte], offset: Int): (T, Int) // Returns (value, nextOffset)
}

object BinaryReader {

  /**
   * Implicit reader for Short values (little endian)
   */
  implicit val shortReader: BinaryReader[Short] = new BinaryReader[Short] {
    def read(bytes: Array[Byte], offset: Int): (Short, Int) = {
      if (offset < 0 || offset + 2 > bytes.length) {
        throw new ArrayIndexOutOfBoundsException(
          s"Cannot read 2 bytes at offset $offset from array of length ${bytes.length}"
        )
      }
      val value = ((bytes(offset + 1) << 8) | (bytes(offset) & 0xff)).toShort
      (value, offset + 2)
    }
  }

  /**
   * Implicit reader for Int values (little endian)
   */
  implicit val intReader: BinaryReader[Int] = new BinaryReader[Int] {
    def read(bytes: Array[Byte], offset: Int): (Int, Int) = {
      if (offset < 0 || offset + 4 > bytes.length) {
        throw new ArrayIndexOutOfBoundsException(
          s"Cannot read 4 bytes at offset $offset from array of length ${bytes.length}"
        )
      }
      val value = (bytes(offset) & 0xff) |
        ((bytes(offset + 1) & 0xff) << 8) |
        ((bytes(offset + 2) & 0xff) << 16) |
        ((bytes(offset + 3) & 0xff) << 24)
      (value, offset + 4)
    }
  }

  /**
   * Extension methods for Array[Byte] to enable contextual reading
   */
  implicit class ByteArrayOps(bytes: Array[Byte]) {
    def read[T](offset: Int)(implicit reader: BinaryReader[T]): (T, Int) =
      reader.read(bytes, offset)
  }
}
