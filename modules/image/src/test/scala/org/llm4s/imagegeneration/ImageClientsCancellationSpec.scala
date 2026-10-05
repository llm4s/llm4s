package org.llm4s.imagegeneration

import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.provider.{
  HttpClient,
  HuggingFaceClient,
  OpenAIImageClient,
  StabilityAIClient,
  StableDiffusionClient
}
import org.llm4s.imageprocessing.config.{ AnthropicVisionConfig, GeminiVisionConfig, OpenAIVisionConfig }
import org.llm4s.imageprocessing.provider.OpenAIVisionClient
import org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient
import org.llm4s.imageprocessing.provider.geminiclient.GeminiVisionClient
import org.llm4s.testkit.{ LocalProviderTestServer, ProviderModuleChecks }
import org.scalatest.flatspec.AnyFlatSpec

import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.util.Try

/**
 * Every image client honours interruption (design section 4.4): called on a virtual thread against a
 * server that never answers, then interrupted, it returns `Left(CancelledError)` promptly with the
 * thread's interrupt flag still set - not an `ImageUnknownError`, not an `ImageServiceError`.
 */
class ImageClientsCancellationSpec extends AnyFlatSpec {

  /** Runs `test` with the base URL of a server that holds every request open. */
  private def withHeldServer(test: String => Any): Unit =
    LocalProviderTestServer.withServer("/")(LocalProviderTestServer.holdOpen)(test)

  /** Sends whatever a client addresses to `baseUrl` instead, keeping the path and query. */
  private class Redirecting(baseUrl: String) extends HttpClient {
    private val real = HttpClient.create()

    private def local(url: String): String = {
      val uri = new URI(url)
      baseUrl + uri.getRawPath + Option(uri.getRawQuery).fold("")("?" + _)
    }

    def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration): Try[HttpResponse] =
      real.post(local(url), headers, data, timeout)
    def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = real.postBytes(local(url), headers, data, timeout)
    def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = real.postMultipart(local(url), headers, data, timeout)
    def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] =
      real.get(local(url), headers, timeout)
    def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpRawResponse] = real.postRaw(local(url), headers, data, timeout)
  }

  private def png(): String = {
    val file = Files.createTempFile("llm4s-cancel-", ".png")
    file.toFile.deleteOnExit()
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", file.toFile): Unit
    file.toString
  }

  // ---- image generation

  "OpenAIImageClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new OpenAIImageClient(OpenAIConfig(apiKey = "k", baseUrl = url + "/v1"), HttpClient.create())
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("openai-image generateImage")(client.generateImage("a cat"))
  }

  "StabilityAIClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new StabilityAIClient(StabilityAIConfig(apiKey = "k", baseUrl = url), HttpClient.create())
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("stability-ai generateImage")(client.generateImage("a cat"))
  }

  "StableDiffusionClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new StableDiffusionClient(StableDiffusionConfig(baseUrl = url), HttpClient.create())
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("stable-diffusion generateImage")(
      client.generateImage("a cat")
    )
  }

  "HuggingFaceClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = "k"), new Redirecting(url))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("huggingface generateImage")(client.generateImage("a cat"))
  }

  // ---- image processing

  "OpenAIVisionClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new OpenAIVisionClient(OpenAIVisionConfig(apiKey = "k", baseUrl = url + "/v1"))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("openai-vision analyzeImage")(client.analyzeImage(png()))
  }

  "AnthropicVisionClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new AnthropicVisionClient(AnthropicVisionConfig(apiKey = "k", baseUrl = url))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("anthropic-vision analyzeImage")(client.analyzeImage(png()))
  }

  "GeminiVisionClient" should "return CancelledError when its thread is interrupted" in withHeldServer { url =>
    val client = new GeminiVisionClient(GeminiVisionConfig(apiKey = "k", baseUrl = url))
    ProviderModuleChecks.assertCallCancelsWhenInterrupted("gemini-vision analyzeImage")(client.analyzeImage(png()))
  }
}
