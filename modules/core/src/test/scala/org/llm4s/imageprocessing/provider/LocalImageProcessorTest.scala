package org.llm4s.imageprocessing.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.imageprocessing._
import java.nio.file.{ Files, Paths, Path }
import java.awt.image.BufferedImage
import java.awt.Color
import javax.imageio.ImageIO

class LocalImageProcessorTest extends AnyFlatSpec with Matchers {

  val processor = new LocalImageProcessor()

  private def withTempImage[T](ext: String = ".png")(test: Path => T): T = {
    val tempFile = Files.createTempFile("test", ext)
    try test(tempFile)
    finally Files.deleteIfExists(tempFile)
  }

  def createTestImage(width: Int, height: Int): BufferedImage = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g2d   = image.createGraphics()
    g2d.setColor(Color.RED)
    g2d.fillRect(0, 0, width, height)
    g2d.setColor(Color.BLUE)
    g2d.fillRect(width / 4, height / 4, width / 2, height / 2)
    g2d.dispose()
    image
  }

  def saveTestImage(image: BufferedImage, path: String): Unit =
    ImageIO.write(image, "png", Paths.get(path).toFile)

  "LocalImageProcessor" should "analyze image with basic information" in
    withTempImage() { tempFile =>
      val testImage = createTestImage(100, 100)
      saveTestImage(testImage, tempFile.toString)

      val result = processor.analyzeImage(tempFile.toString, None)
      result.isRight shouldBe true

      result.foreach { analysis =>
        analysis.description should include("100x100")
        analysis.description should include("color")
        analysis.confidence should be > 0.0
        analysis.confidence should be <= 1.0
        analysis.tags should not be empty
        analysis.metadata.originalPath shouldBe Some(tempFile.toString)
      }
    }

  it should "resize image successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(200, 200)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.resizeImage(tempFile.toString, 100, 100, maintainAspectRatio = false)
    result.isRight shouldBe true

    result.foreach { processedImage =>
      processedImage.width shouldBe 100
      processedImage.height shouldBe 100
      processedImage.format shouldBe ImageFormat.PNG
      processedImage.data.length should be > 0
      processedImage.metadata.operations should contain(ImageOperation.Resize(100, 100, false))
    }
  }

  it should "maintain aspect ratio when resizing" in withTempImage() { tempFile =>
    val testImage = createTestImage(200, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.resizeImage(tempFile.toString, 100, 100, maintainAspectRatio = true)
    result.isRight shouldBe true

    result.foreach { processedImage =>
      processedImage.width shouldBe 100
      processedImage.height shouldBe 50
    }
  }

  it should "crop image successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(200, 200)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Crop(50, 50, 100, 100)))
    result.isRight shouldBe true

    result.foreach { processedImage =>
      processedImage.width shouldBe 100
      processedImage.height shouldBe 100
      processedImage.metadata.operations should contain(ImageOperation.Crop(50, 50, 100, 100))
    }
  }

  it should "rotate image successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 200)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Rotate(90.0)))
    result.isRight shouldBe true

    result.foreach { processedImage =>
      processedImage.width shouldBe 200
      processedImage.height shouldBe 100
      processedImage.metadata.operations should contain(ImageOperation.Rotate(90.0))
    }
  }

  it should "apply blur successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Blur(5.0)))
    result.isRight shouldBe true
  }

  it should "adjust brightness successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Brightness(50)))
    result.isRight shouldBe true
  }

  it should "adjust contrast successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Contrast(25)))
    result.isRight shouldBe true
  }

  it should "convert to grayscale successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Grayscale))
    result.isRight shouldBe true
  }

  it should "convert format successfully" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.convertFormat(tempFile.toString, ImageFormat.JPEG)
    result.isRight shouldBe true

    result.foreach { processedImage =>
      processedImage.format shouldBe ImageFormat.JPEG
      processedImage.width shouldBe 100
      processedImage.height shouldBe 100
    }
  }

  it should "handle multiple operations in sequence" in withTempImage() { tempFile =>
    val testImage = createTestImage(200, 200)
    saveTestImage(testImage, tempFile.toString)

    val operations = List(
      ImageOperation.Resize(100, 100),
      ImageOperation.Blur(3.0),
      ImageOperation.Brightness(20)
    )

    val result = processor.preprocessImage(tempFile.toString, operations)
    result.isRight shouldBe true
  }

  it should "fail with invalid file path" in {
    val result = processor.analyzeImage("/nonexistent/file.png", None)
    result.isLeft shouldBe true
  }

  it should "fail with invalid image file" in withTempImage(".txt") { tempFile =>
    Files.write(tempFile, "This is not an image".getBytes)
    val result = processor.analyzeImage(tempFile.toString, None)
    result.isLeft shouldBe true
  }

  it should "fail with invalid resize dimensions" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.resizeImage(tempFile.toString, -1, 100, maintainAspectRatio = false)
    result.isLeft shouldBe true
  }

  it should "fail with invalid crop dimensions" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.preprocessImage(tempFile.toString, List(ImageOperation.Crop(200, 200, 100, 100)))
    result.isLeft shouldBe true
  }

  it should "handle WEBP format with fallback" in withTempImage() { tempFile =>
    val testImage = createTestImage(100, 100)
    saveTestImage(testImage, tempFile.toString)

    val result = processor.convertFormat(tempFile.toString, ImageFormat.WEBP)
    result.isRight shouldBe true

    result.foreach(processedImage => processedImage.format shouldBe ImageFormat.WEBP)
  }
}
