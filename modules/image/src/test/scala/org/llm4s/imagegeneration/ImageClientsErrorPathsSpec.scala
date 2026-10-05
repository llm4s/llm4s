package org.llm4s.imagegeneration

import org.llm4s.error.LLMError
import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.provider.{
  HttpClient,
  HuggingFaceClient,
  OpenAIImageClient,
  StabilityAIClient,
  StableDiffusionClient
}
import org.llm4s.testkit.LocalProviderTestServer
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.nio.file.{ Files, Path }
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext }
import scala.util.{ Failure, Try }

/**
 * What every image client does when the HTTP layer itself breaks - a transport failure, or an exception
 * thrown outright - rather than the provider answering with an error: the caller gets a `Left` that names
 * the cause, and nothing throws. No server is involved; the HTTP client is a stub that always fails.
 */
class ImageClientsErrorPathsSpec extends AnyFlatSpec with Matchers {

  implicit private val ec: ExecutionContext = ExecutionContext.global

  private val boom = new IllegalStateException("the connection broke")

  /** An HTTP client whose every call returns a failed `Try` (a transport failure the client reports). */
  private object Failing extends HttpClient {
    private def fail[A]: Try[A] = Failure(boom)

    def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration): Try[HttpResponse] = fail
    def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = fail
    def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = fail
    def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = fail
    def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpRawResponse] =
      fail
  }

  /** An HTTP client that throws instead of returning, so an exception escapes the call itself. */
  private object Throwing extends HttpClient {
    private def die[A]: A = throw boom

    def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration): Try[HttpResponse] = die
    def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = die
    def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = die
    def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = die
    def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpRawResponse] =
      die
  }

  private def png(): Path = {
    val file = Files.createTempFile("llm4s-error-paths-", ".png")
    file.toFile.deleteOnExit()
    ImageIO.write(new BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB), "png", file.toFile): Unit
    file
  }

  private def await[A](future: scala.concurrent.Future[A]): A = Await.result(future, 20.seconds)

  private def message(result: Either[LLMError, ?]): String =
    result.left.getOrElse(fail("expected a Left")).message

  private def clients(http: HttpClient): Seq[(String, ImageGenerationClient)] = Seq(
    "openai"           -> new OpenAIImageClient(OpenAIConfig(apiKey = "k"), http),
    "stability-ai"     -> new StabilityAIClient(StabilityAIConfig(apiKey = "k"), http),
    "stable-diffusion" -> new StableDiffusionClient(StableDiffusionConfig(baseUrl = "http://localhost:1"), http),
    "huggingface"      -> new HuggingFaceClient(HuggingFaceConfig(apiKey = "k"), http)
  )

  // ---- a transport failure the client reports

  "every image client" should "report a failed HTTP call as a Left that names the cause" in {
    clients(Failing).foreach { case (name, client) =>
      withClue(s"$name generateImage: ")(message(client.generateImage("a cat")) should include("the connection broke"))
    }
  }

  it should "report a failed HTTP call from generateImages as a Left" in {
    clients(Failing).foreach { case (name, client) =>
      withClue(s"$name generateImages: ")(client.generateImages("a cat", 2).isLeft shouldBe true)
    }
  }

  it should "report a failed health check as a Left, not as a healthy or degraded status" in {
    Seq("openai", "stability-ai", "huggingface").foreach { name =>
      val client = clients(Failing).collectFirst { case (`name`, c) => c }.get
      withClue(s"$name health: ")(message(client.health()) should include("Health check failed"))
    }
  }

  "OpenAIImageClient" should "report a failed upload from editImage as a Left" in {
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = "k"), Failing)
    val result = client.editImage(png(), "make it blue")
    message(result) should include("the connection broke")
  }

  // ---- an exception thrown outright, through the async API

  "every image client's async API" should "return a Left when the HTTP layer throws, not a failed future" in {
    clients(Throwing).foreach { case (name, client) =>
      val one   = await(client.generateImageAsync("a cat"))
      val many  = await(client.generateImagesAsync("a cat", 2))
      val edits = await(client.editImageAsync(png(), "make it blue"))
      withClue(s"$name generateImageAsync: ")(one.isLeft shouldBe true)
      withClue(s"$name generateImagesAsync: ")(many.isLeft shouldBe true)
      withClue(s"$name editImageAsync: ")(edits.isLeft shouldBe true)
    }
  }

  // ---- the health check

  "ImageGeneration.healthCheck" should "report a reachable service as healthy and a failing one as degraded" in {
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 200, "{}")) { url =>
      ImageGeneration.healthCheck(StableDiffusionConfig(baseUrl = url)).map(_.status) shouldBe Right(
        HealthStatus.Healthy
      )
    }
    LocalProviderTestServer.withServer("/")(ex => LocalProviderTestServer.sendJsonResponse(ex, 503, "{}")) { url =>
      ImageGeneration.healthCheck(StableDiffusionConfig(baseUrl = url)).map(_.status) shouldBe Right(
        HealthStatus.Degraded
      )
    }
  }

  "an image client with no health probe of its own" should "report its health as unknown, not as healthy" in {
    val bare = new ImageGenerationClient {
      def generateImage(prompt: String, options: ImageGenerationOptions) = Left(ImageServiceError("unused", 500))
      def generateImages(prompt: String, count: Int, options: ImageGenerationOptions) =
        Left(ImageServiceError("unused", 500))
    }

    bare.health().map(_.status) shouldBe Right(HealthStatus.Unknown)
  }
}
