package org.llm4s.imageprocessing.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import com.sun.net.httpserver.{ HttpExchange, HttpHandler, HttpServer }
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.awt.image.BufferedImage
import java.awt.Color
import javax.imageio.ImageIO
import scala.concurrent.duration.*

import ch.qos.logback.classic.{ Logger => LBLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

class VisionClientsSpec extends AnyFunSuite with Matchers {

  // The stub server below responds immediately, so these bound nothing the test is
  // waiting for - they only have to outlast the first HTTP call in a cold JVM. At one
  // second they did not on Windows CI: the call timed out before the stubbed 500
  // arrived, which is still a `Left` but logs nothing to assert on.
  private val RequestTimeout = 30.seconds
  private val ConnectTimeout = 10.seconds

  private def createTestImage(width: Int = 10, height: Int = 10): BufferedImage = {
    val image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    val g2d   = image.createGraphics()
    g2d.setColor(Color.RED)
    g2d.fillRect(0, 0, width, height)
    g2d.dispose()
    image
  }

  private def withTempImageFile[A](f: String => A): A = {
    val tempFile = Files.createTempFile("test-vision", ".jpg")
    try {
      val testImage = createTestImage()
      ImageIO.write(testImage, "jpg", tempFile.toFile)
      f(tempFile.toString)
    } finally Files.deleteIfExists(tempFile)
  }

  @scala.annotation.nowarn
  private def withTestServer(
    port: Int = 0,
    delayMs: Long = 0L,
    status: Int = 500,
    body: String = "error",
    path: String = "/chat/completions"
  )(f: Int => Unit): Unit = {
    val server = HttpServer.create(new InetSocketAddress(port), 0)
    server.createContext(
      path,
      new HttpHandler {
        override def handle(t: HttpExchange): Unit = {
          if (delayMs > 0) Thread.sleep(delayMs)
          val resp = body.getBytes(StandardCharsets.UTF_8)
          t.getResponseHeaders.add("Content-Type", "application/json")
          t.sendResponseHeaders(status, resp.length)
          val os = t.getResponseBody
          os.write(resp)
          os.close()
        }
      }
    )
    server.setExecutor(Executors.newCachedThreadPool())
    server.start()
    try f(server.getAddress.getPort)
    finally
      server.stop(0)
  }

  test("OpenAIVisionClient: non-200 -> Failure and log body is truncated") {
    val longBody = "E" * 5000
    withTestServer(0, status = 500, body = longBody, path = "/chat/completions") { port =>
      withTempImageFile { imagePath =>
        val cfg = org.llm4s.imageprocessing.config.OpenAIVisionConfig(
          apiKey = "x",
          baseUrl = s"http://localhost:$port",
          requestTimeout = RequestTimeout,
          connectTimeout = ConnectTimeout
        )
        val client = new org.llm4s.imageprocessing.provider.OpenAIVisionClient(cfg)

        val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LBLogger]
        val appender   = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)

        // Use the public API instead of reflection
        val result = client.analyzeImage(imagePath, Some("test prompt"))

        // The failure must be the stubbed 500 itself. `isLeft` alone also holds for a
        // timeout or a refused connection, which log nothing and would leave the
        // truncation assertion below failing as a bare `false was not equal to true`.
        val error = result.left.getOrElse(fail(s"expected a failure, got $result")).formatted
        error should include("Status 500")

        // The error body must not be logged in full — the log should contain a truncated marker
        val logged =
          appender.list.toArray.map(_.asInstanceOf[ch.qos.logback.classic.spi.ILoggingEvent].getFormattedMessage)
        logged.exists(_.contains("(truncated, original length:")) shouldBe true

        // Cleanup
        rootLogger.detachAppender(appender)
      }
    }
  }

  test("AnthropicVisionClient: non-200 -> Failure and log body is truncated") {
    val longBody = "E" * 5000
    withTestServer(0, status = 500, body = longBody, path = "/v1/messages") { port =>
      withTempImageFile { imagePath =>
        val cfg = org.llm4s.imageprocessing.config.AnthropicVisionConfig(
          apiKey = "x",
          baseUrl = s"http://localhost:$port",
          requestTimeout = RequestTimeout,
          connectTimeout = ConnectTimeout
        )
        val client = new org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient(cfg)

        val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LBLogger]
        val appender   = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)

        // Use the public API instead of reflection
        val result = client.analyzeImage(imagePath, Some("test prompt"))

        // The failure must be the stubbed 500 itself. `isLeft` alone also holds for a
        // timeout or a refused connection, which log nothing and would leave the
        // truncation assertion below failing as a bare `false was not equal to true`.
        val error = result.left.getOrElse(fail(s"expected a failure, got $result")).formatted
        error should include("Status 500")

        // The error body must not be logged in full — the log should contain a truncated marker
        val logged =
          appender.list.toArray.map(_.asInstanceOf[ch.qos.logback.classic.spi.ILoggingEvent].getFormattedMessage)
        logged.exists(_.contains("(truncated, original length:")) shouldBe true

        // Cleanup
        rootLogger.detachAppender(appender)
      }
    }
  }

  /** A local port nothing is listening on, so connecting is refused at once. */
  private def closedPort(): Int = {
    val socket = new java.net.ServerSocket(0)
    val port   = socket.getLocalPort
    socket.close()
    port
  }

  test("OpenAIVisionClient: a refused connection is a Left, never an exception") {
    withTempImageFile { imagePath =>
      val cfg = org.llm4s.imageprocessing.config.OpenAIVisionConfig(
        apiKey = "x",
        baseUrl = s"http://localhost:${closedPort()}",
        requestTimeout = RequestTimeout,
        connectTimeout = ConnectTimeout
      )
      val error = new org.llm4s.imageprocessing.provider.OpenAIVisionClient(cfg)
        .analyzeImage(imagePath, Some("p"))
        .left
        .getOrElse(fail("expected a failure"))
      error.formatted should include("OpenAI API call failed")
    }
  }

  test("OpenAIVisionClient: a blank apiKey is refused before any request, never sent as an empty bearer") {
    withTempImageFile { imagePath =>
      val cfg = org.llm4s.imageprocessing.config.OpenAIVisionConfig(
        apiKey = " ",
        baseUrl = s"http://localhost:${closedPort()}",
        requestTimeout = RequestTimeout,
        connectTimeout = ConnectTimeout
      )
      val error = new org.llm4s.imageprocessing.provider.OpenAIVisionClient(cfg)
        .analyzeImage(imagePath, Some("p"))
        .left
        .getOrElse(fail("expected a failure"))
      error shouldBe a[org.llm4s.error.ConfigurationError]
      error.message should include("apiKey")
    }
  }

  test("AnthropicVisionClient: a refused connection is a Left, never an exception") {
    withTempImageFile { imagePath =>
      val cfg = org.llm4s.imageprocessing.config.AnthropicVisionConfig(
        apiKey = "x",
        baseUrl = s"http://localhost:${closedPort()}",
        requestTimeout = RequestTimeout,
        connectTimeout = ConnectTimeout
      )
      val error = new org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient(cfg)
        .analyzeImage(imagePath, Some("p"))
        .left
        .getOrElse(fail("expected a failure"))
      error.formatted should include("Anthropic API call failed")
    }
  }

  // ── Replies the vision clients parse ───────────────────────────────

  private def openAI(port: Int) =
    new org.llm4s.imageprocessing.provider.OpenAIVisionClient(
      org.llm4s.imageprocessing.config.OpenAIVisionConfig(
        apiKey = "x",
        baseUrl = s"http://localhost:$port",
        requestTimeout = RequestTimeout,
        connectTimeout = ConnectTimeout
      )
    )

  private def anthropic(port: Int) =
    new org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient(
      org.llm4s.imageprocessing.config.AnthropicVisionConfig(
        apiKey = "x",
        baseUrl = s"http://localhost:$port",
        requestTimeout = RequestTimeout,
        connectTimeout = ConnectTimeout
      )
    )

  private val Description = """A person walks a dog past a car. The sign text says "open late"."""

  test("OpenAIVisionClient: a 200 is parsed into description, tags, objects and text") {
    val reply = ujson.Obj("choices" -> ujson.Arr(ujson.Obj("message" -> ujson.Obj("content" -> Description)))).render()
    withTestServer(0, status = 200, body = reply, path = "/chat/completions") { port =>
      withTempImageFile { imagePath =>
        val client = openAI(port)
        val result = client.analyzeImage(imagePath, Some("p")).getOrElse(fail("expected a result"))
        result.description shouldBe Description
        (result.tags should contain).allOf("person", "dog", "car")
        (result.objects.map(_.label) should contain).allOf("person", "dog", "car")
        result.text shouldBe Some("open late")
        client.extractText(imagePath) shouldBe Right("open late")
        client.detectObjects(imagePath).map(_.map(_.label).toSet) shouldBe Right(Set("person", "dog", "car"))
        client.generateTags(imagePath).map(_.contains("dog")) shouldBe Right(true)
      }
    }
  }

  test("OpenAIVisionClient: a 200 whose body is not JSON is reported in the description") {
    withTestServer(0, status = 200, body = "not json", path = "/chat/completions") { port =>
      withTempImageFile { imagePath =>
        openAI(port).analyzeImage(imagePath, Some("p")).map(_.description) shouldBe
          Right("Could not parse response from OpenAI Vision API")
      }
    }
  }

  test("OpenAIVisionClient: a 200 without choices is a failure, not an exception") {
    withTestServer(0, status = 200, body = """{"unexpected":true}""", path = "/chat/completions") { port =>
      withTempImageFile { imagePath =>
        val error = openAI(port).analyzeImage(imagePath, Some("p")).left.getOrElse(fail("expected a failure"))
        error.formatted should include("choices")
      }
    }
  }

  test("OpenAIVisionClient: an error reply's code, type or message is reported") {
    val cases = Seq(
      """{"error":{"message":"bad image","code":"invalid_image"}}"""         -> "invalid_image: bad image",
      """{"error":{"message":"bad image","type":"invalid_request_error"}}""" -> "invalid_request_error: bad image",
      """{"error":{"message":"bad image"}}"""                                -> "Status 400: bad image",
      """not json"""                                                         -> "Status 400: not json"
    )
    cases.foreach { case (body, expected) =>
      withTestServer(0, status = 400, body = body, path = "/chat/completions") { port =>
        withTempImageFile { imagePath =>
          val error = openAI(port).analyzeImage(imagePath, Some("p")).left.getOrElse(fail("expected a failure"))
          withClue(body)(error.formatted should include(expected))
        }
      }
    }
  }

  test("AnthropicVisionClient: a 200 is parsed into description, tags, objects and text") {
    val reply = ujson.Obj("content" -> ujson.Arr(ujson.Obj("type" -> "text", "text" -> Description))).render()
    withTestServer(0, status = 200, body = reply, path = "/v1/messages") { port =>
      withTempImageFile { imagePath =>
        val client = anthropic(port)
        val result = client.analyzeImage(imagePath, Some("p")).getOrElse(fail("expected a result"))
        result.description shouldBe Description
        (result.tags should contain).allOf("person", "dog", "car")
        (result.objects.map(_.label) should contain).allOf("person", "dog", "car")
        result.text shouldBe Some("open late")
        client.extractText(imagePath) shouldBe Right("open late")
        client.detectObjects(imagePath).map(_.map(_.label).toSet) shouldBe Right(Set("person", "dog", "car"))
        client.generateTags(imagePath).map(_.contains("dog")) shouldBe Right(true)
      }
    }
  }

  test("AnthropicVisionClient: a 200 whose body is not JSON is reported in the description") {
    withTestServer(0, status = 200, body = "not json", path = "/v1/messages") { port =>
      withTempImageFile { imagePath =>
        anthropic(port).analyzeImage(imagePath, Some("p")).map(_.description) shouldBe
          Right("Could not parse response from Anthropic Vision API")
      }
    }
  }

  test("AnthropicVisionClient: a 200 without content is a failure, not an exception") {
    withTestServer(0, status = 200, body = """{"unexpected":true}""", path = "/v1/messages") { port =>
      withTempImageFile { imagePath =>
        val error = anthropic(port).analyzeImage(imagePath, Some("p")).left.getOrElse(fail("expected a failure"))
        error.formatted should include("content")
      }
    }
  }

  test("AnthropicVisionClient: an error reply's type or message is reported") {
    val cases = Seq(
      """{"error":{"type":"invalid_request_error","message":"bad image"}}""" -> "invalid_request_error: bad image",
      """{"error":{"message":"bad image"}}"""                                -> "Status 400: bad image",
      """not json"""                                                         -> "Status 400: not json"
    )
    cases.foreach { case (body, expected) =>
      withTestServer(0, status = 400, body = body, path = "/v1/messages") { port =>
        withTempImageFile { imagePath =>
          val error = anthropic(port).analyzeImage(imagePath, Some("p")).left.getOrElse(fail("expected a failure"))
          withClue(body)(error.formatted should include(expected))
        }
      }
    }
  }
}
