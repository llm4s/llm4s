package org.llm4s.imagegeneration.provider

import scala.concurrent.duration.FiniteDuration

import org.llm4s.http.{ HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.{
  ImageGenerationOptions,
  ImageServiceError,
  ImageSize,
  ImageValidationError,
  OpenAIConfig,
  TransientImageServiceError
}
import org.llm4s.media.MediaType
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.util.{ Success, Try }

class OpenAIImageClientGenerationValidationTest extends AnyFlatSpec with Matchers {

  final private class StubHttpClient(
    postResponse: HttpResponse = HttpResponse(200, """{"data":[{"b64_json":"Zm9v"}]}"""),
    getResponse: HttpResponse = HttpResponse(200, "{}")
  ) extends HttpClient {
    var lastPostBody: Option[String] = None

    override def post(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpResponse] = {
      lastPostBody = Some(data)
      Success(postResponse)
    }

    override def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = Success(postResponse)

    override def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = Success(postResponse)

    override def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = Success(
      getResponse
    )

    override def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ) = scala.util.Failure(new UnsupportedOperationException("not used in this test"))
  }

  private def client(config: OpenAIConfig, httpClient: HttpClient = new StubHttpClient()): OpenAIImageClient =
    new OpenAIImageClient(config, httpClient)

  "generateImages" should "reject counts above 1 for dall-e-3 before any remote call" in {
    val c = client(OpenAIConfig(apiKey = "test-key", model = "dall-e-3"))

    val result = c.generateImages(prompt = "a test prompt", count = 2)

    result shouldBe Left(ImageValidationError("Count must be between 1 and 1 for dall-e-3"))
  }

  it should "reject empty prompts before any remote call" in {
    val c = client(OpenAIConfig(apiKey = "test-key"))

    val result = c.generateImages(prompt = "   ", count = 1)

    result shouldBe Left(ImageValidationError("Prompt cannot be empty"))
  }

  it should "enforce 1000-char prompt limit for dall-e-2" in {
    val c      = client(OpenAIConfig(apiKey = "test-key", model = "dall-e-2"))
    val prompt = "x" * 1001

    val result = c.generateImages(prompt = prompt, count = 1)

    result shouldBe Left(ImageValidationError("Prompt cannot exceed 1000 characters for dall-e-2"))
  }

  it should "enforce 32000-char prompt limit for gpt-image models" in {
    val c      = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1.5"))
    val prompt = "x" * 32001

    val result = c.generateImages(prompt = prompt, count = 1)

    result shouldBe Left(ImageValidationError("Prompt cannot exceed 32000 characters for gpt-image-1.5"))
  }

  it should "reject unsupported generation response format before any remote call" in {
    val c = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"))

    val result = c.generateImages(
      prompt = "a test prompt",
      count = 1,
      options = ImageGenerationOptions(responseFormat = Some("xml"))
    )

    result shouldBe Left(ImageValidationError("Unsupported response format for generation: xml"))
  }

  it should "reject an unsupported output format before any remote call" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"), stub)

    val result = c.generateImages(
      prompt = "a test prompt",
      count = 1,
      options = ImageGenerationOptions(outputFormat = Some("gif"))
    )

    result shouldBe Left(ImageValidationError("Unsupported output format: gif"))
    stub.lastPostBody shouldBe None
  }

  it should "reject out-of-range output compression before any remote call" in {
    val c = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"))

    val result = c.generateImages(
      prompt = "a test prompt",
      count = 1,
      options = ImageGenerationOptions(outputCompression = Some(101))
    )

    result shouldBe Left(ImageValidationError("Output compression must be between 0 and 100, got: 101"))
  }

  it should "not send response_format when caller does not set it" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"), stub)

    c.generateImages(prompt = "prompt", count = 1) shouldBe a[Right[_, _]]

    val sent = ujson.read(stub.lastPostBody.getOrElse(fail("expected post body"))).obj
    sent.contains("response_format") shouldBe false
    sent("quality").str shouldBe "medium"
  }

  it should "apply dall-e-3 default quality and size mapping" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = "test-key", model = "dall-e-3"), stub)

    c.generateImages(
      prompt = "prompt",
      count = 1,
      options = ImageGenerationOptions(size = ImageSize.Landscape1536x1024)
    ) shouldBe a[Right[_, _]]

    val sent = ujson.read(stub.lastPostBody.getOrElse(fail("expected post body"))).obj
    sent("quality").str shouldBe "standard"
    sent("size").str shouldBe "1792x1024"
  }

  it should "derive image format from requested output_format when response is url" in {
    val stub = new StubHttpClient(postResponse = HttpResponse(200, """{"data":[{"url":"https://x/y.png"}]}"""))
    val c    = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"), stub)

    val result = c.generateImages(
      prompt = "prompt",
      count = 1,
      options = ImageGenerationOptions(outputFormat = Some("jpeg"), responseFormat = Some("url"))
    )

    result match {
      case Right(images) =>
        images.head.format shouldBe MediaType.Jpeg
        images.head.url shouldBe Some("https://x/y.png")
      case Left(err) => fail(s"expected success, got $err")
    }
  }

  it should "surface api 400 error message from provider payload" in {
    val stub = new StubHttpClient(
      postResponse = HttpResponse(400, """{"error":{"message":"bad request details"}}""")
    )
    val c = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"), stub)

    val result = c.generateImages(prompt = "prompt", count = 1)

    result shouldBe Left(ImageValidationError("Invalid request: bad request details"))
  }

  it should "surface any other error status as an ImageServiceError carrying that status" in {
    val stub = new StubHttpClient(
      postResponse = HttpResponse(503, """{"error":{"message":"overloaded"}}""")
    )
    val c = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"), stub)

    val result = c.generateImages(prompt = "prompt", count = 1)

    result shouldBe Left(ImageServiceError("API error: overloaded", 503))
    result.left.toOption.get shouldBe a[TransientImageServiceError]
  }

  it should "map malformed response body to ImageUnknownError" in {
    val stub = new StubHttpClient(postResponse = HttpResponse(200, "not-json"))
    val c    = client(OpenAIConfig(apiKey = "test-key", model = "gpt-image-1"), stub)

    val result = c.generateImages(prompt = "prompt", count = 1)

    result.left.toOption.get shouldBe a[org.llm4s.imagegeneration.ImageUnknownError]
  }
}
