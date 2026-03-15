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

import ch.qos.logback.classic.{ Logger => LBLogger }
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender

class VisionClientsSpec extends AnyFunSuite with Matchers {

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
    } finally
      Files.deleteIfExists(tempFile)
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
          requestTimeoutSeconds = 1,
          connectTimeoutSeconds = 1
        )
        val client = new org.llm4s.imageprocessing.provider.OpenAIVisionClient(cfg)

        val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LBLogger]
        val appender   = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)

        // Use the public API instead of reflection
        val result = client.analyzeImage(imagePath, Some("test prompt"))

        // The API should fail with an error
        result.isLeft shouldBe true

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
          requestTimeoutSeconds = 1,
          connectTimeoutSeconds = 1
        )
        val client = new org.llm4s.imageprocessing.provider.anthropicclient.AnthropicVisionClient(cfg)

        val rootLogger = org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LBLogger]
        val appender   = new ListAppender[ILoggingEvent]()
        appender.start()
        rootLogger.addAppender(appender)

        // Use the public API instead of reflection
        val result = client.analyzeImage(imagePath, Some("test prompt"))

        // The API should fail with an error
        result.isLeft shouldBe true

        // The error body must not be logged in full — the log should contain a truncated marker
        val logged =
          appender.list.toArray.map(_.asInstanceOf[ch.qos.logback.classic.spi.ILoggingEvent].getFormattedMessage)
        logged.exists(_.contains("(truncated, original length:")) shouldBe true

        // Cleanup
        rootLogger.detachAppender(appender)
      }
    }
  }

}
