package org.llm4s.imageprocessing.provider

import org.llm4s.error.InvalidInputError
import org.llm4s.imageprocessing.ImageOperation
import org.llm4s.imageprocessing.config.{ AnthropicVisionConfig, OpenAIVisionConfig }
import org.llm4s.media.MediaType
import org.llm4s.security.Secret
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.{ Files, Path }
import java.util.zip.CRC32
import javax.imageio.ImageIO

/**
 * The local processor's limits (#3139): a small PNG declaring huge dimensions is refused from its header,
 * an operation that would allocate too much or nothing is a `Left`, and blur and contrast ranges are checked.
 */
class LocalImageProcessorLimitsSpec extends AnyFlatSpec with Matchers {

  private val processor = new LocalImageProcessor()

  private def pngBytes(width: Int, height: Int): Array[Byte] = {
    val out = new ByteArrayOutputStream()
    ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", out)
    out.toByteArray
  }

  /** A real 8 x 8 PNG whose IHDR is rewritten to declare `width` x `height`, with a valid CRC. */
  private def pngDeclaring(width: Int, height: Int): Array[Byte] = {
    val bytes = pngBytes(8, 8)
    // Signature (8), IHDR length (4), "IHDR" (4), then width and height, then 5 more bytes and the CRC.
    ByteBuffer.wrap(bytes, 16, 8).putInt(width).putInt(height)
    val crc = new CRC32()
    crc.update(bytes, 12, 17)
    ByteBuffer.wrap(bytes, 29, 4).putInt(crc.getValue.toInt)
    bytes
  }

  private def withFile[A](bytes: Array[Byte])(f: String => A): A = {
    val file: Path = Files.createTempFile("llm4s-limits-", ".png")
    Files.write(file, bytes)
    try f(file.toString)
    finally Files.deleteIfExists(file): Unit
  }

  "LocalImageProcessor" should "refuse to decode a small PNG declaring 50000 x 50000 pixels" in
    withFile(pngDeclaring(50000, 50000)) { path =>
      val result = processor.preprocessImage(path, List(ImageOperation.Grayscale))
      result.swap.toOption.get shouldBe an[InvalidInputError]
      result.swap.toOption.get.asInstanceOf[InvalidInputError].reason should include("pixel limit")

      processor.convertFormat(path, MediaType.Jpeg).swap.toOption.get shouldBe an[InvalidInputError]
    }

  it should "analyse such a file from its header, without decoding it" in
    withFile(pngDeclaring(50000, 50000)) { path =>
      processor.analyzeImage(path).map(_.description) match {
        case Right(description) => description should include("50000x50000")
        case Left(error)        => fail(error.message)
      }
    }

  it should "refuse a file over the byte limit before reading it" in
    withFile(pngBytes(64, 64)) { path =>
      val small = new LocalImageProcessor(maxFileBytes = 10)
      small.analyzeImage(path).swap.toOption.get shouldBe an[InvalidInputError]
      small.preprocessImage(path, Nil).swap.toOption.get.asInstanceOf[InvalidInputError].reason should include(
        "byte limit"
      )
    }

  it should "refuse a resize to more pixels than the limit" in
    withFile(pngBytes(16, 16)) { path =>
      val result = processor.resizeImage(path, 100000, 100000, maintainAspectRatio = false)
      result.swap.toOption.get shouldBe an[InvalidInputError]
    }

  it should "keep at least one pixel when an elongated image is fitted into a small box" in
    withFile(pngBytes(1000, 2)) { path =>
      val result = processor.resizeImage(path, 10, 10)
      result.map(r => (r.width, r.height)) shouldBe Right((10, 1))
    }

  it should "refuse an out-of-range blur radius and contrast or brightness level" in
    withFile(pngBytes(16, 16)) { path =>
      List(
        ImageOperation.Blur(500),
        ImageOperation.Blur(-1),
        ImageOperation.Contrast(259),
        ImageOperation.Contrast(-101),
        ImageOperation.Brightness(101)
      ).foreach(op => processor.preprocessImage(path, List(op)).swap.toOption.get shouldBe an[InvalidInputError])
      processor.preprocessImage(path, List(ImageOperation.Contrast(100))).isRight shouldBe true
    }

  it should "blur a large image in linear time" in
    withFile(pngBytes(2000, 2000)) { path =>
      val start  = System.nanoTime()
      val result = processor.preprocessImage(path, List(ImageOperation.Blur(100)))
      result.isRight shouldBe true
      // The direct 2-D box took minutes at this size and radius; the separable one takes well under a second.
      (System.nanoTime() - start) should be < 30L * 1000 * 1000 * 1000
    }

  it should "average the in-bounds window, as the direct box blur did" in {
    val image = new BufferedImage(5, 1, BufferedImage.TYPE_INT_RGB)
    List(0, 30, 60, 90, 120).zipWithIndex.foreach { case (v, x) => image.setRGB(x, 0, (v << 16) | (v << 8) | v) }
    val out = new ByteArrayOutputStream()
    ImageIO.write(image, "png", out)
    withFile(out.toByteArray) { path =>
      val blurred = processor.preprocessImage(path, List(ImageOperation.Blur(1))).toOption.get
      val decoded = ImageIO.read(new java.io.ByteArrayInputStream(blurred.data))
      (0 until 5).map(x => decoded.getRGB(x, 0) & 0xff) shouldBe Seq(15, 30, 60, 90, 105)
    }
  }

  "The vision clients" should "refuse to upload a file over the upload limit" in {
    val big = Files.createTempFile("llm4s-limits-big-", ".png")
    try {
      Files.write(big, new Array[Byte]((BoundedImageIO.MaxUploadBytes + 1).toInt))
      new OpenAIVisionClient(OpenAIVisionConfig(Secret("k"))).encodeImageToBase64(big.toString).isFailure shouldBe true
      new anthropicclient.AnthropicVisionClient(AnthropicVisionConfig(Secret("k")))
        .encodeImageToBase64(big.toString)
        .isFailure shouldBe true
    } finally Files.deleteIfExists(big): Unit
  }
}
