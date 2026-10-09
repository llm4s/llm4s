package org.llm4s.imageprocessing.provider

import ch.qos.logback.classic.{ Logger => LogbackLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.sun.net.httpserver.HttpServer
import org.llm4s.error.LLMError
import org.llm4s.http.{ HttpRawResponse, HttpResponse, MultipartPart }
import org.llm4s.imagegeneration.{ HuggingFaceConfig, OpenAIConfig, StableDiffusionConfig }
import org.llm4s.imagegeneration.provider.{ HttpClient, HuggingFaceClient, OpenAIImageClient, StableDiffusionClient }
import org.llm4s.imageprocessing.ImageProcessingClient
import org.llm4s.imageprocessing.config.{ AnthropicVisionConfig, GeminiVisionConfig, OpenAIVisionConfig }
import org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient
import org.llm4s.imageprocessing.provider.geminiclient.GeminiVisionClient
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.slf4j.LoggerFactory

import java.awt.image.BufferedImage
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import javax.imageio.ImageIO
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*
import scala.util.{ Success, Try }

/**
 * An error body that echoes the request's credentials back must not reach the caller's error or the log in the clear
 * (#1674): every image client redacts the body before it truncates it.
 */
class ErrorBodyRedactionSpec extends AnyFlatSpec with Matchers {

  private val BearerToken = "ya29.bearer-secret-token-value"
  private val ApiKeyValue = "s3cr3t-api-key-value"
  private val GoogleKey   = "AIzaSyA1234567890abcdefghijklmnopqrstuv"
  private val Secrets     = Seq(BearerToken, ApiKeyValue, GoogleKey)

  /** A body echoing an `Authorization` header, an `api_key` field and a `?key=` query parameter. */
  private val EchoedText =
    s"Authorization: Bearer $BearerToken\n" +
      s"""{"api_key": "$ApiKeyValue"}""" + "\n" +
      s"GET /v1beta/models?key=$GoogleKey"

  /** The same text as the `message` of a provider's JSON error, which the vision clients extract. */
  private val EchoedJsonError =
    ujson.Obj("error" -> ujson.Obj("message" -> EchoedText, "type" -> "bad_request")).render()

  private def assertNoSecret(text: String): Unit =
    Secrets.foreach(secret => withClue(s"in: $text\n")((text should not).include(secret)))

  private def errorOf(result: Either[LLMError, ?]): String =
    result.left.getOrElse(fail(s"expected a failure, got $result")).formatted

  /** Runs `f` with a stub answering every request with `status` and `body`, and returns its result and the log. */
  private def withServer[A](status: Int, body: String)(f: String => A): (A, Seq[String]) = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext(
      "/",
      exchange => {
        val bytes = body.getBytes(StandardCharsets.UTF_8)
        exchange.getResponseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.length.toLong)
        exchange.getResponseBody.write(bytes)
        exchange.close()
      }
    )
    server.start()
    val root     = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]()
    appender.start()
    root.addAppender(appender)
    val result = Try(f(s"http://127.0.0.1:${server.getAddress.getPort}"))
    root.detachAppender(appender)
    server.stop(0)
    (result.get, appender.list.asScala.toSeq.map(_.getFormattedMessage))
  }

  private def withImage[A](f: String => A): A = {
    val path = Files.createTempFile("redaction", ".png")
    val img  = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB)
    ImageIO.write(img, "png", path.toFile)
    val result = Try(f(path.toString))
    Files.deleteIfExists(path)
    result.get
  }

  private def openAI(baseUrl: String) =
    new OpenAIVisionClient(OpenAIVisionConfig(apiKey = "x", baseUrl = baseUrl, requestTimeout = 10.seconds))

  private def anthropic(baseUrl: String) =
    new AnthropicVisionClient(AnthropicVisionConfig(apiKey = "x", baseUrl = baseUrl, requestTimeout = 10.seconds))

  private def gemini(baseUrl: String) =
    new GeminiVisionClient(GeminiVisionConfig(apiKey = "x", baseUrl = baseUrl, requestTimeoutSeconds = 10))

  private val visionClients: Seq[(String, String => ImageProcessingClient)] =
    Seq("openai" -> openAI, "anthropic" -> anthropic, "gemini" -> gemini)

  "every vision client" should "redact credentials echoed in a non-JSON error body, in its error and its log" in {
    visionClients.foreach { case (name, client) =>
      withClue(s"$name: ") {
        val (result, logged) =
          withServer(400, EchoedText)(url => withImage(path => client(url).analyzeImage(path, Some("p"))))
        val error = errorOf(result)
        error should include("[REDACTED]")
        assertNoSecret(error)
        logged.foreach(assertNoSecret)
      }
    }
  }

  it should "redact credentials echoed in the message of a JSON error body" in {
    visionClients.foreach { case (name, client) =>
      withClue(s"$name: ") {
        val (result, logged) =
          withServer(400, EchoedJsonError)(url => withImage(path => client(url).analyzeImage(path, Some("p"))))
        val error = errorOf(result)
        error should include("[REDACTED]")
        assertNoSecret(error)
        logged.foreach(assertNoSecret)
      }
    }
  }

  /** An image-generation transport that answers every call with `status` and `body`. */
  private class Answering(status: Int, body: String) extends HttpClient {
    private def reply = Success(HttpResponse(status, body))
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
    ): Try[HttpRawResponse] = Success(HttpRawResponse(status, body.getBytes(StandardCharsets.UTF_8)))
  }

  "StableDiffusionClient and HuggingFaceClient" should "redact credentials echoed in an error body" in {
    val http = new Answering(500, EchoedText)
    Seq(
      "stable-diffusion" -> new StableDiffusionClient(StableDiffusionConfig(baseUrl = "http://localhost:1"), http),
      "huggingface"      -> new HuggingFaceClient(HuggingFaceConfig(apiKey = "k"), http)
    ).foreach { case (name, client) =>
      withClue(s"$name: ") {
        val error = client.generateImage("a cat").left.getOrElse(fail("expected a failure")).message
        error should include("[REDACTED]")
        assertNoSecret(error)
      }
    }
  }

  "OpenAIImageClient" should "redact credentials echoed in an error body, raw or as a JSON error's message" in {
    for {
      status <- Seq(400, 500)
      body   <- Seq(EchoedText, EchoedJsonError)
    } withClue(s"$status, ${body.take(20)}: ") {
      val client = new OpenAIImageClient(OpenAIConfig(apiKey = "k"), new Answering(status, body))
      val error  = client.generateImage("a cat").left.getOrElse(fail("expected a failure")).message
      error should include("[REDACTED]")
      assertNoSecret(error)
    }
  }
}
