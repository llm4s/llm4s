package org.llm4s.imagegeneration.provider

import org.llm4s.imagegeneration.{ ImageEditOptions, ProviderImageEditOptions, StableDiffusionConfig, ValidationError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.util.Using

class StableDiffusionClientEditTest extends AnyFlatSpec with Matchers {

  "editImage" should "fail when source image does not exist" in {
    val client =
      new StableDiffusionClient(StableDiffusionConfig(baseUrl = "http://localhost:7860"), HttpClient.create())

    val result = client.editImage(
      imagePath = java.nio.file.Path.of("does-not-exist.png"),
      prompt = "add clouds"
    )

    result should matchPattern { case Left(_: ValidationError) => }
  }

  it should "fail when mask dimensions do not match source image dimensions" in {
    val client =
      new StableDiffusionClient(StableDiffusionConfig(baseUrl = "http://localhost:7860"), HttpClient.create())

    withTempFiles("sd-source", "sd-mask") { (source, mask) =>
      writePng(source, width = 128, height = 128)
      writePng(mask, width = 64, height = 64)

      val result = client.editImage(
        imagePath = source,
        prompt = "remove foreground object",
        maskPath = Some(mask),
        options = ImageEditOptions(n = 1)
      )

      result should matchPattern { case Left(_: ValidationError) => }
    }
  }

  it should "fail when denoising strength is out of range" in {
    val client =
      new StableDiffusionClient(StableDiffusionConfig(baseUrl = "http://localhost:7860"), HttpClient.create())

    withTempFiles("sd-source", "sd-mask") { (source, _) =>
      writePng(source, width = 128, height = 128)

      val result = client.editImage(
        imagePath = source,
        prompt = "remove object",
        options = ImageEditOptions(
          n = 1,
          providerOptions = Some(ProviderImageEditOptions.StableDiffusion(denoisingStrength = Some(1.5)))
        )
      )

      result shouldBe Left(ValidationError("denoisingStrength must be between 0.0 and 1.0, got: 1.5"))
    }
  }

  it should "fail when provider options are for another provider" in {
    val client =
      new StableDiffusionClient(StableDiffusionConfig(baseUrl = "http://localhost:7860"), HttpClient.create())

    val result = client.editImage(
      imagePath = java.nio.file.Path.of("does-not-exist.png"),
      prompt = "add clouds",
      options = ImageEditOptions(
        providerOptions = Some(ProviderImageEditOptions.OpenAI(responseFormat = Some("b64_json")))
      )
    )

    result shouldBe Left(
      ValidationError("Unsupported provider-specific edit options for Stable Diffusion image client")
    )
  }

  private def writePng(path: java.nio.file.Path, width: Int, height: Int): Unit = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    ImageIO.write(image, "png", path.toFile)
  }

  private def withTempFiles[A](sourcePrefix: String, maskPrefix: String)(
    f: (java.nio.file.Path, java.nio.file.Path) => A
  ): A =
    Using.Manager { use =>
      val source = use(tempFile(sourcePrefix))
      val mask   = use(tempFile(maskPrefix))
      f(source.path, mask.path)
    }.get

  private def tempFile(prefix: String): TempFile =
    TempFile(Files.createTempFile(prefix, ".png"))

  private case class TempFile(path: java.nio.file.Path) extends AutoCloseable {
    override def close(): Unit = Files.deleteIfExists(path)
  }
}
