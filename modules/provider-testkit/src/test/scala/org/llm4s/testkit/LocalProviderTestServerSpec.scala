package org.llm4s.testkit

import org.llm4s.testkit.LocalProviderTestServer.*
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import scala.util.Try

class LocalProviderTestServerSpec extends AnyWordSpec with Matchers:

  private val http = HttpClient.newHttpClient()

  private def get(url: String): HttpResponse[String] =
    http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString())

  "withServer" should {

    "serve the handler on the path, then stop" in {
      var served = ""
      withServer("/v1/chat")(exchange => sendJsonResponse(exchange, 201, """{"ok":true}""")) { baseUrl =>
        served = baseUrl
        val response = get(s"$baseUrl/v1/chat")
        response.statusCode shouldBe 201
        response.body shouldBe """{"ok":true}"""
        response.headers.firstValue("Content-Type").orElse("") shouldBe "application/json"
      }
      Try(get(s"$served/v1/chat")).isFailure shouldBe true
    }

    "serve an event stream" in {
      withServer("/")(exchange => sendSseResponse(exchange, "data: x\n\n")) { baseUrl =>
        val response = get(s"$baseUrl/")
        response.statusCode shouldBe 200
        response.body shouldBe "data: x\n\n"
        response.headers.firstValue("Content-Type").orElse("") shouldBe "text/event-stream"
      }
    }

    "stop the server and rethrow when the test throws" in {
      var served = ""
      val thrown = intercept[IllegalStateException] {
        withServer("/")(exchange => sendJsonResponse(exchange, 200, "{}")) { baseUrl =>
          served = baseUrl
          throw new IllegalStateException("boom")
        }
      }
      thrown.getMessage shouldBe "boom"
      Try(get(s"$served/")).isFailure shouldBe true
    }
  }

  "the OpenAI-format bodies" should {

    "be a parseable completion" in {
      val json = ujson.read(openAICompletion("He said \"hi\"", model = "m"))
      json("model").str shouldBe "m"
      json("choices")(0)("message")("content").str shouldBe "He said \"hi\""
    }

    "be one data event per chunk, the last finishing, then [DONE]" in {
      val events = openAISseBody(Seq("a", "b")).split("\n\n").toSeq
      events.last shouldBe "data: [DONE]"
      val chunks = events.init.map(e => ujson.read(e.stripPrefix("data: ")))
      chunks.map(_("choices")(0)("delta")("content").str) shouldBe Seq("a", "b")
      chunks.map(_("choices")(0)("finish_reason").strOpt) shouldBe Seq(None, Some("stop"))
      chunks.last.obj.contains("usage") shouldBe true
    }
  }
