package org.llm4s.extract

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

class MediaExtractorSpec extends AnyFunSuite with Matchers {

  private def withTempFile(extension: String, content: String)(test: File => Unit): Unit = {
    val file = Files.createTempFile("media-", extension).toFile
    try {
      Files.write(file.toPath, content.getBytes("UTF-8"))
      test(file)
    } finally file.delete()
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
}
