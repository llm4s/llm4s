package org.llm4s.imagegeneration.provider

import org.llm4s.security.Secret
import scala.concurrent.duration.FiniteDuration

import org.llm4s.error.ConfigurationError
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
    val c = client(OpenAIConfig(apiKey = Secret("test-key"), model = "dall-e-3"))

    val result = c.generateImages(prompt = "a test prompt", count = 2)

    result shouldBe Left(ImageValidationError("Count must be between 1 and 1 for dall-e-3"))
  }

  it should "refuse a blank apiKey before any remote call, rather than send an empty bearer" in {
    for key <- Seq("", "  ") do
      val http = new StubHttpClient()
      val c    = client(OpenAIConfig(apiKey = Secret(key), model = "gpt-image-2.6-sunburst"), http)
      c.generateImages(prompt = "a test prompt", count = 1).left.toOption.get shouldBe a[ConfigurationError]
      c.health().left.toOption.get shouldBe a[ConfigurationError]
      http.lastPostBody shouldBe None
  }

  it should "reject empty prompts before any remote call" in {
    val c = client(OpenAIConfig(apiKey = Secret("test-key")))

    val result = c.generateImages(prompt = "   ", count = 1)

    result shouldBe Left(ImageValidationError("Prompt cannot be empty"))
  }

  it should "enforce 1000-char prompt limit for dall-e-2" in {
    val c      = client(OpenAIConfig(apiKey = Secret("test-key"), model = "dall-e-2"))
    val prompt = "x" * 1001

    val result = c.generateImages(prompt = prompt, count = 1)

    result shouldBe Left(ImageValidationError("Prompt cannot exceed 1000 characters for dall-e-2"))
  }

  it should "enforce 32000-char prompt limit for gpt-image models" in {
    val c      = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-1.5"))
    val prompt = "x" * 32001

    val result = c.generateImages(prompt = prompt, count = 1)

    result shouldBe Left(ImageValidationError("Prompt cannot exceed 32000 characters for gpt-image-1.5"))
  }

  it should "reject unsupported generation response format before any remote call" in {
    val c = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"))

    val result = c.generateImages(
      prompt = "a test prompt",
      count = 1,
      options = ImageGenerationOptions(responseFormat = Some("xml"))
    )

    result shouldBe Left(ImageValidationError("Unsupported response format for generation: xml"))
  }

  it should "reject an unsupported output format before any remote call" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"), stub)

    val result = c.generateImages(
      prompt = "a test prompt",
      count = 1,
      options = ImageGenerationOptions(outputFormat = Some("gif"))
    )

    result shouldBe Left(ImageValidationError("Unsupported output format: gif"))
    stub.lastPostBody shouldBe None
  }

  it should "reject out-of-range output compression before any remote call" in {
    val c = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"))

    val result = c.generateImages(
      prompt = "a test prompt",
      count = 1,
      options = ImageGenerationOptions(outputCompression = Some(101))
    )

    result shouldBe Left(ImageValidationError("Output compression must be between 0 and 100, got: 101"))
  }

  it should "not send response_format when caller does not set it" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"), stub)

    c.generateImages(prompt = "prompt", count = 1) shouldBe a[Right[_, _]]

    val sent = ujson.read(stub.lastPostBody.getOrElse(fail("expected post body"))).obj
    sent.contains("response_format") shouldBe false
    sent("quality").str shouldBe "medium"
  }

  it should "apply dall-e-3 default quality and size mapping" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = Secret("test-key"), model = "dall-e-3"), stub)

    c.generateImages(
      prompt = "prompt",
      count = 1,
      options = ImageGenerationOptions(size = ImageSize.Landscape1536x1024)
    ) shouldBe a[Right[_, _]]

    val sent = ujson.read(stub.lastPostBody.getOrElse(fail("expected post body"))).obj
    sent("quality").str shouldBe "standard"
    sent("size").str shouldBe "1792x1024"
  }

  it should "read a legacy model's url response and its format from the url" in {
    val stub = new StubHttpClient(postResponse = HttpResponse(200, """{"data":[{"url":"https://x/y.webp"}]}"""))
    val c    = client(OpenAIConfig(apiKey = Secret("test-key"), model = "dall-e-3"), stub)

    val result =
      c.generateImages(prompt = "prompt", count = 1, options = ImageGenerationOptions(responseFormat = Some("url")))

    result match {
      case Right(images) =>
        images.head.format shouldBe MediaType.WebP
        images.head.url shouldBe Some("https://x/y.webp")
      case Left(err) => fail(s"expected success, got $err")
    }
    ujson.read(stub.lastPostBody.getOrElse(fail("expected post body")))("response_format").str shouldBe "url"
  }

  it should "default to the current GPT Image model, not a DALL·E one (#2884)" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = Secret("test-key")), stub)

    c.generateImages(prompt = "prompt", count = 1) shouldBe a[Right[_, _]]

    ujson.read(stub.lastPostBody.getOrElse(fail("expected post body")))("model").str shouldBe "gpt-image-2.6-sunburst"
  }

  it should "never send response_format to a GPT Image model, which always returns base64" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = Secret("test-key")), stub)

    c.generateImages(
      prompt = "prompt",
      count = 1,
      options = ImageGenerationOptions(responseFormat = Some("b64_json"))
    ) shouldBe a[
      Right[_, _]
    ]

    ujson.read(stub.lastPostBody.getOrElse(fail("expected post body"))).obj.contains("response_format") shouldBe false
  }

  it should "refuse a url response or a style for a GPT Image model before any remote call" in {
    val stub = new StubHttpClient()
    val c    = client(OpenAIConfig(apiKey = Secret("test-key")), stub)

    c.generateImages("prompt", 1, ImageGenerationOptions(responseFormat = Some("url"))) shouldBe Left(
      ImageValidationError(
        "gpt-image-2.6-sunburst always returns base64 image data: response_format 'url' is only for the retired DALL·E models"
      )
    )
    c.generateImages("prompt", 1, ImageGenerationOptions(style = Some("vivid"))) shouldBe Left(
      ImageValidationError("style is not supported by gpt-image-2.6-sunburst: describe the style in the prompt")
    )
    stub.lastPostBody shouldBe None
  }

  it should "send the GPT Image output options and read the format the response reports" in {
    val stub = new StubHttpClient(
      postResponse = HttpResponse(200, """{"created":1,"output_format":"webp","data":[{"b64_json":"Zm9v"}]}""")
    )
    val c = client(OpenAIConfig(apiKey = Secret("test-key")), stub)

    val result = c.generateImages(
      prompt = "prompt",
      count = 1,
      options = ImageGenerationOptions(
        size = ImageSize.Portrait1024x1536,
        quality = Some("high"),
        background = Some("opaque")
      )
    )

    result.map(_.map(_.format)) shouldBe Right(Seq(MediaType.WebP))
    val sent = ujson.read(stub.lastPostBody.getOrElse(fail("expected post body"))).obj
    sent("size").str shouldBe "1024x1536"
    sent("quality").str shouldBe "high"
    sent("background").str shouldBe "opaque"
  }

  it should "surface api 400 error message from provider payload" in {
    val stub = new StubHttpClient(
      postResponse = HttpResponse(400, """{"error":{"message":"bad request details"}}""")
    )
    val c = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"), stub)

    val result = c.generateImages(prompt = "prompt", count = 1)

    result shouldBe Left(ImageValidationError("Invalid request: bad request details"))
  }

  it should "surface any other error status as an ImageServiceError carrying that status" in {
    val stub = new StubHttpClient(
      postResponse = HttpResponse(503, """{"error":{"message":"overloaded"}}""")
    )
    val c = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"), stub)

    val result = c.generateImages(prompt = "prompt", count = 1)

    result shouldBe Left(ImageServiceError("API error: overloaded", 503))
    result.left.toOption.get shouldBe a[TransientImageServiceError]
  }

  it should "map malformed response body to ImageUnknownError" in {
    val stub = new StubHttpClient(postResponse = HttpResponse(200, "not-json"))
    val c    = client(OpenAIConfig(apiKey = Secret("test-key"), model = "gpt-image-2.6-sunburst"), stub)

    val result = c.generateImages(prompt = "prompt", count = 1)

    result.left.toOption.get shouldBe a[org.llm4s.imagegeneration.ImageUnknownError]
  }
}
