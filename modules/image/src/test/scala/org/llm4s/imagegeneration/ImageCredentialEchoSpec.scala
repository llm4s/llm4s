package org.llm4s.imagegeneration

import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.provider.*
import org.llm4s.imageprocessing.config.{ AnthropicVisionConfig, GeminiVisionConfig, OpenAIVisionConfig }
import org.llm4s.imageprocessing.provider.OpenAIVisionClient
import org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient
import org.llm4s.imageprocessing.provider.geminiclient.GeminiVisionClient
import org.llm4s.security.{ KnownSecrets, Secret }
import org.llm4s.testkit.CredentialEchoChecks
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.awt.image.BufferedImage
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.util.{ Success, Try }

/** A key the provider echoes back in its 401 body never reaches the `LLMError` (#1932). */
class ImageCredentialEchoSpec extends AnyFlatSpec with Matchers {

  private val echoKey = "echo-key-0123456789abcdef"

  /** An image-generation transport that answers every call with 401 and the echoing body. */
  private object Answering extends HttpClient {
    private val body  = CredentialEchoChecks.echoBody(echoKey)
    private def reply = Success(HttpResponse(401, body))
    def post(url: String, headers: Map[String, String], data: String, timeout: FiniteDuration): Try[HttpResponse] =
      reply
    def postBytes(
      url: String,
      headers: Map[String, String],
      data: Array[Byte],
      timeout: FiniteDuration
    ): Try[HttpResponse] = reply
    def postMultipart(
      url: String,
      headers: Map[String, String],
      data: Seq[MultipartPart],
      timeout: FiniteDuration
    ): Try[HttpResponse] = reply
    def get(url: String, headers: Map[String, String], timeout: FiniteDuration): Try[HttpResponse] = reply
    def postRaw(
      url: String,
      headers: Map[String, String],
      data: String,
      timeout: FiniteDuration
    ): Try[HttpRawResponse] = Success(HttpRawResponse(401, body.getBytes(StandardCharsets.UTF_8)))
  }

  private def withImage[A](f: String => A): A = {
    val path = Files.createTempFile("echo", ".png")
    ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB), "png", path.toFile): Unit
    val result = Try(f(path.toString))
    Files.deleteIfExists(path): Unit
    result.get
  }

  "OpenAIImageClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = OpenAIConfig(apiKey = Secret(echoKey), baseUrl = s"$baseUrl/v1")
      new OpenAIImageClient(config, HttpClient.create()).generateImage("a cat")
    }
  }

  "StabilityAIClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = StabilityAIConfig(apiKey = Secret(echoKey), baseUrl = baseUrl)
      new StabilityAIClient(config, HttpClient.create()).generateImage("a cat")
    }
  }

  "StableDiffusionClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = StableDiffusionConfig(baseUrl = baseUrl, apiKey = Some(Secret(echoKey)))
      new StableDiffusionClient(config, HttpClient.create()).generateImage("a cat")
    }
  }

  // HuggingFaceClient hard-codes its endpoint, so it is reached through the HTTP client it is given.
  "HuggingFaceClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    val client = new HuggingFaceClient(HuggingFaceConfig(apiKey = Secret(echoKey)), Answering)
    CredentialEchoChecks.assertHidesKey(echoKey, client.generateImage("a cat"))
  }

  "OpenAIVisionClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = OpenAIVisionConfig(apiKey = Secret(echoKey), baseUrl = baseUrl, requestTimeout = 10.seconds)
      withImage(path => new OpenAIVisionClient(config).analyzeImage(path, Some("p")))
    }
  }

  "AnthropicVisionClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = AnthropicVisionConfig(apiKey = Secret(echoKey), baseUrl = baseUrl, requestTimeout = 10.seconds)
      withImage(path => new AnthropicVisionClient(config).analyzeImage(path, Some("p")))
    }
  }

  "GeminiVisionClient" should "keep an echoed key out of the error" in KnownSecrets.isolated {
    CredentialEchoChecks.assertNotEchoed(echoKey) { baseUrl =>
      val config = GeminiVisionConfig(apiKey = Secret(echoKey), baseUrl = baseUrl, requestTimeoutSeconds = 10)
      withImage(path => new GeminiVisionClient(config).analyzeImage(path, Some("p")))
    }
  }
}
