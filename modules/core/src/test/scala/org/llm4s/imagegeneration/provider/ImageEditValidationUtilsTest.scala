package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.{ ImageSize, ValidationError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.util.Using

class ImageEditValidationUtilsTest extends AnyFlatSpec with Matchers {

  "readImageFile" should "read bytes for existing files" in {
    val path = Files.createTempFile("image-edit-utils-bytes", ".bin")
    try {
      Files.write(path, Array[Byte](1, 2, 3))
      val result = ImageEditValidationUtils.readImageFile(path, "source")
      result match {
        case Right(bytes) => bytes.toSeq shouldBe Seq[Byte](1, 2, 3)
        case Left(error)  => fail(s"Expected bytes, got error: $error")
      }
    } finally Files.deleteIfExists(path)
  }

  it should "return validation error for missing files" in {
    val result = ImageEditValidationUtils.readImageFile(java.nio.file.Path.of("missing.bin"), "source")
    result should matchPattern { case Left(_: ValidationError) => }
  }

  "readImageSize" should "return validation error for non-image files" in {
    val path = Files.createTempFile("image-edit-utils-not-image", ".txt")
    try {
      Files.write(path, "not-an-image".getBytes("UTF-8"))
      val result = ImageEditValidationUtils.readImageSize(path, "source")
      result should matchPattern { case Left(_: ValidationError) => }
    } finally Files.deleteIfExists(path)
  }

  "validateMaskDimensions" should "return validation error for mismatched dimensions" in {
    withTempImage(64, 64) { source =>
      withTempImage(32, 32) { mask =>
        val sourceSize = ImageEditValidationUtils.readImageSize(source, "source").toOption.get
        val result     = ImageEditValidationUtils.validateMaskDimensions(sourceSize, Some(mask))
        result should matchPattern { case Left(_: ValidationError) => }
      }
    }
  }

  "toImageSize" should "map unknown dimensions to custom" in {
    ImageEditValidationUtils.toImageSize(333, 222) shouldBe ImageSize.Custom(333, 222)
  }

  private def withTempImage[A](width: Int, height: Int)(f: java.nio.file.Path => A): A =
    Using.resource(TempPng(width, height))(png => f(png.path))

  private case class TempPng(path: java.nio.file.Path) extends AutoCloseable {
    override def close(): Unit = Files.deleteIfExists(path)
  }

  private object TempPng {
    def apply(width: Int, height: Int): TempPng = {
      val path  = Files.createTempFile("image-edit-utils", ".png")
      val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
      ImageIO.write(image, "png", path.toFile)
      TempPng(path)
    }
  }
}
