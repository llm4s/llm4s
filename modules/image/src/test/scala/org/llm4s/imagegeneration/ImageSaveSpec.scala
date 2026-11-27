package org.llm4s.imagegeneration

import org.llm4s.error.InvalidInputError
import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.security.Secret
import org.llm4s.imageprocessing.{ ImageMetadata, ProcessedImage }
import org.llm4s.media.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }
import java.util.Base64
import scala.jdk.CollectionConverters._
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Try, Using }

/**
 * Saving an image (#3140): an image without inline data is a `Left` and writes nothing, an existing file is
 * kept unless `overwrite` is set, nothing partial is left behind, and decoding never throws.
 */
class ImageSaveSpec extends AnyFlatSpec with Matchers {

  private def withDir[A](f: Path => A): A = {
    val dir = Files.createTempDirectory("llm4s-image-save-")
    try f(dir)
    finally Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p)))
  }

  private def listing(dir: Path): Set[String] =
    Using.resource(Files.list(dir))(_.iterator().asScala.map(_.getFileName.toString).toSet)

  private val payload = "png bytes".getBytes("UTF-8")

  private def image(data: String, url: Option[String] = None) =
    GeneratedImage(data = data, format = MediaType.Png, size = ImageSize.Square512, prompt = "p", url = url)

  "GeneratedImage.saveToFile" should "refuse an image with only a URL, writing no file" in withDir { dir =>
    val target = dir.resolve("out.png")
    val result = image("", url = Some("https://example.com/i.png")).saveToFile(target)

    result.swap.toOption.get shouldBe an[ImageValidationError]
    result.swap.toOption.get.message should include("https://example.com/i.png")
    listing(dir) shouldBe empty
  }

  it should "keep an existing file unless overwrite is set" in withDir { dir =>
    val target = Files.writeString(dir.resolve("out.png"), "the user's file")
    val img    = image(Base64.getEncoder.encodeToString(payload))

    img.saveToFile(target).swap.toOption.get shouldBe an[ImageValidationError]
    Files.readString(target) shouldBe "the user's file"

    img.saveToFile(target, overwrite = true).map(_.filePath) shouldBe Right(Some(target))
    Files.readAllBytes(target).toSeq shouldBe payload.toSeq
    listing(dir) shouldBe Set("out.png") // no temporary file left beside it
  }

  it should "leave nothing behind when the write fails" in withDir { dir =>
    val result = image(Base64.getEncoder.encodeToString(payload)).saveToFile(dir.resolve("missing").resolve("x.png"))
    result.isLeft shouldBe true
    listing(dir) shouldBe empty
  }

  "GeneratedImage.bytes" should "be a Left, not an exception, for invalid base64 or no data" in {
    image("not base64 !!").bytes.swap.toOption.get shouldBe an[ImageValidationError]
    image("").bytes.isLeft shouldBe true
    image(Base64.getEncoder.encodeToString(payload)).bytes.map(_.toSeq) shouldBe Right(payload.toSeq)
  }

  "ProcessedImage.saveToFile" should "not overwrite an existing file, such as its source, by default" in withDir {
    dir =>
      val source    = Files.writeString(dir.resolve("photo.png"), "original")
      val processed = ProcessedImage(payload, MediaType.Png, 1, 1, ImageMetadata(originalPath = Some(source.toString)))

      processed.saveToFile(source).swap.toOption.get shouldBe an[InvalidInputError]
      Files.readString(source) shouldBe "original"

      processed.saveToFile(source, overwrite = true) shouldBe Right(())
      Files.readAllBytes(source).toSeq shouldBe payload.toSeq
      listing(dir) shouldBe Set("photo.png")
  }

  "The OpenAI image client" should "refuse a response entry with neither b64_json nor url" in {
    val ok = Try(HttpResponse(200, """{"created": 1, "data": [{"revised_prompt": "x"}]}"""))
    val http = new provider.HttpClient {
      def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration)           = ok
      def postBytes(url: String, headers: Map[String, String], data: Array[Byte], timeout: FiniteDuration) = ok
      def postMultipart(url: String, headers: Map[String, String], data: Seq[MultipartPart], timeout: FiniteDuration) =
        ok
      def get(url: String, headers: Map[String, String], timeout: FiniteDuration) = ok
      def postRaw(
        url: String,
        headers: Map[String, String],
        data: String,
        timeout: FiniteDuration
      ): Try[HttpRawResponse] = Failure(new UnsupportedOperationException("postRaw"))
    }
    val client = new provider.OpenAIImageClient(OpenAIConfig(apiKey = Secret("sk-test")), http)

    client.generateImage("a lighthouse").swap.toOption.get shouldBe an[ImageValidationError]
  }
}
