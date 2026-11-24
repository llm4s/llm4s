package org.llm4s.imagegeneration.provider

import org.llm4s.security.Secret
import scala.concurrent.duration.FiniteDuration

import org.llm4s.imagegeneration.{ ImageEditOptions, OpenAIConfig, ProviderImageEditOptions, ImageValidationError }
import org.llm4s.http.{ HttpResponse, MultipartPart }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.util.{ Success, Try }
import scala.util.Using

class OpenAIImageClientEditTest extends AnyFlatSpec with Matchers {

  "editImage" should "fail when source image does not exist" in {
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), HttpClient.create())

    val result = client.editImage(
      imagePath = java.nio.file.Path.of("does-not-exist.png"),
      prompt = "add clouds"
    )

    result should matchPattern { case Left(_: ImageValidationError) => }
  }

  it should "fail when response format is unsupported" in {
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), HttpClient.create())

    val result = client.editImage(
      imagePath = java.nio.file.Path.of("does-not-matter.png"),
      prompt = "add clouds",
      options = ImageEditOptions(
        providerOptions = Some(
          ProviderImageEditOptions.OpenAI(responseFormat = Some("xml"))
        )
      )
    )

    result shouldBe Left(ImageValidationError("Unsupported response format for edit: xml"))
  }

  it should "fail when mask dimensions do not match source image dimensions" in {
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), HttpClient.create())

    withTempFiles("openai-source", "openai-mask") { (source, mask) =>
      writePng(source, width = 64, height = 64)
      writePng(mask, width = 32, height = 32)

      val result = client.editImage(
        imagePath = source,
        prompt = "inpaint sky",
        maskPath = Some(mask)
      )

      result should matchPattern { case Left(_: ImageValidationError) => }
    }
  }

  it should "fail when provider options are for another provider" in {
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), HttpClient.create())

    val result = client.editImage(
      imagePath = java.nio.file.Path.of("does-not-matter.png"),
      prompt = "add clouds",
      options = ImageEditOptions(
        providerOptions = Some(ProviderImageEditOptions.StableDiffusion(denoisingStrength = Some(0.5)))
      )
    )

    result shouldBe Left(ImageValidationError("Unsupported provider-specific edit options for OpenAI image client"))
  }

  it should "fail early when a legacy model's edit output size is unsupported" in {
    val client =
      new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key"), model = "dall-e-2"), HttpClient.create())

    withTempFiles("openai-source-size", "openai-mask-size") { (source, _) =>
      writePng(source, width = 300, height = 300)

      val result = client.editImage(
        imagePath = source,
        prompt = "inpaint sky"
      )

      result should matchPattern { case Left(_: ImageValidationError) => }
    }
  }

  it should "fail when edit endpoint returns no images" in {
    val mockHttpClient = new MockEditHttpClient("""{"data": []}""")
    val client         = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), mockHttpClient)

    withTempFiles("openai-source-empty", "openai-mask-empty") { (source, _) =>
      writePng(source, width = 512, height = 512)

      val result = client.editImage(
        imagePath = source,
        prompt = "inpaint sky"
      )

      result shouldBe Left(ImageValidationError("No images returned from OpenAI image edit endpoint"))
    }
  }

  it should "send the configured model, size auto and no response_format for a GPT Image model (#2884)" in {
    val http   = new MockEditHttpClient("""{"data": [{"b64_json": "Zm9v"}]}""")
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), http)

    withTempFiles("openai-source-gpt", "openai-mask-gpt") { (source, _) =>
      writePng(source, width = 300, height = 200)

      val result = client.editImage(imagePath = source, prompt = "inpaint sky")

      result.map(_.size) shouldBe Right(1)
      val fields = http.textFields
      fields.get("model") shouldBe Some("gpt-image-2.6-sunburst")
      fields.get("size") shouldBe Some("auto")
      fields.contains("response_format") shouldBe false
    }
  }

  it should "send a configured model's name, not a fixed one" in {
    val http = new MockEditHttpClient("""{"data": [{"b64_json": "Zm9v"}]}""")
    val client =
      new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.5-sunburst"), http)

    withTempFiles("openai-source-25", "openai-mask-25") { (source, _) =>
      writePng(source, width = 64, height = 64)

      client.editImage(
        imagePath = source,
        prompt = "inpaint sky",
        options = ImageEditOptions(size = Some(org.llm4s.imagegeneration.ImageSize.Custom(2048, 1152)))
      ) shouldBe a[Right[_, _]]
      http.textFields.get("model") shouldBe Some("gpt-image-2.5-sunburst")
      http.textFields.get("size") shouldBe Some("2048x1152")
    }
  }

  it should "refuse a url response or a style for a GPT Image model's edit" in {
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key")), HttpClient.create())

    def edit(o: ProviderImageEditOptions.OpenAI) =
      client.editImage(
        java.nio.file.Path.of("does-not-matter.png"),
        "add clouds",
        None,
        ImageEditOptions(providerOptions = Some(o))
      )

    edit(ProviderImageEditOptions.OpenAI(responseFormat = Some("url"))).left.toOption.get.message should include(
      "always returns base64"
    )
    edit(ProviderImageEditOptions.OpenAI(style = Some("vivid"))).left.toOption.get.message should include(
      "style is not supported"
    )
  }

  it should "still send response_format to a legacy model" in {
    val http   = new MockEditHttpClient("""{"data": [{"b64_json": "Zm9v"}]}""")
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = Secret("test-key"), model = "dall-e-2"), http)

    withTempFiles("openai-source-legacy", "openai-mask-legacy") { (source, _) =>
      writePng(source, width = 512, height = 512)

      client.editImage(imagePath = source, prompt = "inpaint sky") shouldBe a[Right[_, _]]
      http.textFields.get("model") shouldBe Some("dall-e-2")
      http.textFields.get("response_format") shouldBe Some("b64_json")
      http.textFields.get("size") shouldBe Some("512x512")
    }
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

  private class MockEditHttpClient(body: String) extends HttpClient {
    @volatile var lastParts: Seq[MultipartPart] = Nil
    def textFields: Map[String, String] =
      lastParts.collect { case MultipartPart.TextField(name, value) => name -> value }.toMap
    override def post(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpResponse] = ???
    override def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = ???
    override def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = {
      lastParts = data
      Success(HttpResponse(200, body))
    }
    override def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = ???
    override def postRaw(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration)  = ???
  }
}
