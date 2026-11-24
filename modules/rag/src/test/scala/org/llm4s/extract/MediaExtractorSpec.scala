package org.llm4s.extract

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.io.{ ByteArrayInputStream, File }
import java.nio.file.Files
import javax.imageio.ImageIO
import javax.sound.sampled.{ AudioFileFormat, AudioFormat, AudioInputStream, AudioSystem }

class MediaExtractorSpec extends AnyFunSuite with Matchers {

  // An MP4 `ftyp` box: a 4-byte size, then `ftyp`, the `isom` brand, a version and two compatible brands
  private val minimalMp4: Array[Byte] =
    Array[Byte](0, 0, 0, 24) ++ "ftypisom".getBytes("US-ASCII") ++ Array[Byte](0, 0, 2, 0) ++
      "isomiso2".getBytes("US-ASCII")

  private def withTempFile(extension: String, content: String)(test: File => Unit): Unit = {
    val file = Files.createTempFile("media-", extension).toFile
    try {
      Files.write(file.toPath, content.getBytes("UTF-8"))
      test(file)
    } finally file.delete()
  }

  private def withTempBytes(extension: String, content: Array[Byte])(test: File => Unit): Unit = {
    val file = Files.createTempFile("media-", extension).toFile
    try {
      Files.write(file.toPath, content)
      test(file)
    } finally file.delete()
  }

  // A tenth of a second of 8 kHz, 16-bit mono silence, written as a real WAV file
  private def withSilentWav(extension: String)(test: File => Unit): Unit = {
    val file   = Files.createTempFile("media-", extension).toFile
    val format = new AudioFormat(8000f, 16, 1, true, false)
    val frames = 800
    try {
      val stream = new AudioInputStream(new ByteArrayInputStream(new Array[Byte](frames * 2)), format, frames.toLong)
      AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file)
      test(file)
    } finally file.delete()
  }

  private def processingError(result: Result[MediaExtractor.Extracted]): ProcessingError = result match {
    case Left(error: ProcessingError) => error
    case other                        => fail(s"expected a ProcessingError, got $other")
  }

  test("extractAny should return TextContent for text files") {
    withTempFile(".txt", "Test content") { file =>
      val result = MediaExtractor.extractAny(file.getAbsolutePath)
      result.isRight shouldBe true
      result.toOption.get shouldBe a[MediaExtractor.TextContent]
      result.toOption.get.asInstanceOf[MediaExtractor.TextContent].text shouldBe "Test content"
    }
  }

  test("extractAny should return ImageContent for images") {
    val file = Files.createTempFile("media-", ".png").toFile
    try {
      ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", file)
      val result = MediaExtractor.extractAny(file.getAbsolutePath)
      result.isRight shouldBe true
      val image = result.toOption.get.asInstanceOf[MediaExtractor.ImageContent].image
      image.getWidth shouldBe 4
      image.getHeight shouldBe 3
    } finally file.delete()
  }

  test("extractAny should report an unreadable image rather than throwing") {
    val file = Files.createTempFile("media-", ".png").toFile
    try {
      // PNG magic bytes followed by nothing Tika-readable: sniffed as image/png, unreadable by ImageIO.
      Files.write(file.toPath, Array[Byte](0x89.toByte, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
      val result = MediaExtractor.extractAny(file.getAbsolutePath)
      result.isLeft shouldBe true
    } finally file.delete()
  }

  test("extractAny should return an error for a non-existent file") {
    val result = MediaExtractor.extractAny("/nonexistent/path/file.txt")
    result.isLeft shouldBe true
    result.left.toOption.get.message should include("File not found")
  }

  test("extractAny should honour path normalization") {
    withTempFile(".txt", "Quoted") { file =>
      val result = MediaExtractor.extractAny(s""""${file.getAbsolutePath}"""")
      result.toOption.get.asInstanceOf[MediaExtractor.TextContent].text shouldBe "Quoted"
    }
  }

  test("extractAny should refuse a WAV file as audio it cannot decode yet, naming the MIME type and the path") {
    withSilentWav(".wav") { file =>
      val mimeType = TikaDocumentExtractor.detectMimeType(file)
      mimeType should startWith("audio/")

      // A Left with the audio operation: the file was not handed to the document extractor
      val error = processingError(MediaExtractor.extractAny(file.getAbsolutePath))
      error.operation shouldBe "audio-extraction"
      error.message should include("Audio extraction not yet implemented")
      error.message should include(s"MIME: $mimeType")
      error.message should include(s"(file: ${file.getAbsolutePath})")
    }
  }

  test("extractAny should refuse an MP4 file as video it cannot decode yet, naming the MIME type and the path") {
    withTempBytes(".mp4", minimalMp4) { file =>
      val mimeType = TikaDocumentExtractor.detectMimeType(file)
      mimeType should startWith("video/")

      val error = processingError(MediaExtractor.extractAny(file.getAbsolutePath))
      error.operation shouldBe "video-extraction"
      error.message should include("Video extraction not yet implemented")
      error.message should include(s"MIME: $mimeType")
      error.message should include(s"(file: ${file.getAbsolutePath})")
    }
  }

  test("extractAny should treat a WAV file saved as .bin as audio, because Tika reads its content") {
    withSilentWav(".bin") { file =>
      // Tika reports audio/vnd.wave here, the same as for a .wav name: the content wins over the name
      TikaDocumentExtractor.detectMimeType(file) should startWith("audio/")
      processingError(MediaExtractor.extractAny(file.getAbsolutePath)).operation shouldBe "audio-extraction"
    }
  }
}
