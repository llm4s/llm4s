package org.llm4s.imageprocessing.provider

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.imageprocessing._
import java.nio.file.{ Files, Paths }
import java.awt.image.BufferedImage
import java.awt.Color
import javax.imageio.ImageIO
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class LocalImageProcessorTest extends AnyFlatSpec with Matchers {

  val processor = new LocalImageProcessor()

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

  def withTempImage(width: Int = 100, height: Int = 100)(test: String => Any): Unit = {
    val tempFile  = Files.createTempFile("test", ".png")
    val testImage = createTestImage(width, height)
    saveTestImage(testImage, tempFile.toString)
    try test(tempFile.toString)
    finally Files.deleteIfExists(tempFile)
  }

  "LocalImageProcessor" should "analyze image with basic information" in
    withTempImage(100, 100) { path =>
      val result = processor.analyzeImage(path, None)
      result.isRight shouldBe true

      result.foreach { analysis =>
        analysis.description should include("100x100")
        analysis.description should include("color")
        analysis.confidence should be > 0.0
        analysis.confidence should be <= 1.0
        analysis.tags should not be empty
        analysis.metadata.originalPath shouldBe Some(path)
      }
    }

  it should "analyze image asynchronously" in
    withTempImage(100, 100) { path =>
      val futureResult = processor.analyzeImageAsync(path)
      val result       = Await.result(futureResult, 5.seconds)

      result.isRight shouldBe true

      result.foreach { analysis =>
        analysis.description should include("100x100")
        analysis.confidence should be > 0.0
      }
    }

  it should "resize image successfully" in
    withTempImage(200, 200) { path =>
      val result = processor.resizeImage(path, 100, 100, maintainAspectRatio = false)
      result.isRight shouldBe true

      result.foreach { processedImage =>
        processedImage.width shouldBe 100
        processedImage.height shouldBe 100
        processedImage.format shouldBe ImageFormat.PNG
        processedImage.data.length should be > 0
        processedImage.metadata.operations should contain(ImageOperation.Resize(100, 100, false))
      }
    }

  it should "maintain aspect ratio when resizing" in
    withTempImage(200, 100) { path =>
      val result = processor.resizeImage(path, 100, 100, maintainAspectRatio = true)
      result.isRight shouldBe true

      result.foreach { processedImage =>
        processedImage.width shouldBe 100
        processedImage.height shouldBe 50
      }
    }

  it should "crop image successfully" in
    withTempImage(200, 200) { path =>
      val result = processor.preprocessImage(path, List(ImageOperation.Crop(50, 50, 100, 100)))
      result.isRight shouldBe true

      result.foreach { processedImage =>
        processedImage.width shouldBe 100
        processedImage.height shouldBe 100
        processedImage.metadata.operations should contain(ImageOperation.Crop(50, 50, 100, 100))
      }
    }

  it should "rotate image successfully" in
    withTempImage(100, 200) { path =>
      val result = processor.preprocessImage(path, List(ImageOperation.Rotate(90.0)))
      result.isRight shouldBe true

      result.foreach { processedImage =>
        processedImage.width shouldBe 200
        processedImage.height shouldBe 100
        processedImage.metadata.operations should contain(ImageOperation.Rotate(90.0))
      }
    }

  it should "apply blur successfully" in
    withTempImage() { path =>
      val result = processor.preprocessImage(path, List(ImageOperation.Blur(5.0)))
      result.isRight shouldBe true

      result.foreach(_.metadata.operations should contain(ImageOperation.Blur(5.0)))
    }

  it should "convert format successfully" in
    withTempImage() { path =>
      val result = processor.convertFormat(path, ImageFormat.JPEG)
      result.isRight shouldBe true

      result.foreach { processedImage =>
        processedImage.format shouldBe ImageFormat.JPEG
        processedImage.width shouldBe 100
        processedImage.height shouldBe 100
      }
    }

  it should "handle multiple operations in sequence" in
    withTempImage(200, 200) { path =>
      val operations = List(
        ImageOperation.Resize(100, 100),
        ImageOperation.Blur(3.0),
        ImageOperation.Brightness(20)
      )

      val result = processor.preprocessImage(path, operations)
      result.isRight shouldBe true

      result.foreach { processedImage =>
        processedImage.width shouldBe 100
        processedImage.height shouldBe 100
        (processedImage.metadata.operations should contain).allOf(
          ImageOperation.Resize(100, 100),
          ImageOperation.Blur(3.0),
          ImageOperation.Brightness(20)
        )
      }
    }

  it should "fail with invalid file path" in {
    processor.analyzeImage("/nonexistent/file.png", None).isLeft shouldBe true
  }

  it should "handle WEBP format with fallback" in
    withTempImage() { path =>
      val result = processor.convertFormat(path, ImageFormat.WEBP)
      result.isRight shouldBe true
      result.foreach(_.format shouldBe ImageFormat.WEBP)
    }
}
