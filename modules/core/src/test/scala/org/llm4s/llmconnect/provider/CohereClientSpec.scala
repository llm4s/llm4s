package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.llm4s.error.{ AuthenticationError, RateLimitError, ServiceError, ValidationError }
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class CohereClientSpec extends AnyFlatSpec with Matchers {

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/v2/chat", exchange => handler(exchange))
    server.start()

    val baseUrl = s"http://localhost:${server.getAddress.getPort}"

    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  private def conversation: Conversation = Conversation(Seq(UserMessage("hello")))

  private def config(baseUrl: String): CohereConfig =
    CohereConfig(
      apiKey = "test-key",
      model = "command-r",
      baseUrl = baseUrl,
      contextWindow = 128000,
      reserveCompletion = 4096
    )

  "CohereClient.complete" should "parse a successful v2 response" in withServer { exchange =>
    val body =
      """{
        |  "message": {
        |    "content": [
        |      { "text": "Hello world" }
        |    ]
        |  },
        |  "usage": {
        |    "tokens": {
        |      "input_tokens": 10,
        |      "output_tokens": 5
        |    }
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true

    val completion = result.toOption.get
    completion.content shouldBe "Hello world"
    completion.usage.isDefined shouldBe true
    completion.usage.get.promptTokens shouldBe 10
    completion.usage.get.completionTokens shouldBe 5
  }

  it should "pick the first non-empty text element from message.content" in withServer { exchange =>
    val body =
      """{
        |  "message": {
        |    "content": [
        |      { "text": "   " },
        |      { "type": "text" },
        |      { "text": "  Hello world  " }
        |    ]
        |  },
        |  "usage": {
        |    "tokens": {
        |      "input_tokens": 1,
        |      "output_tokens": 2
        |    }
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isRight shouldBe true

    val completion = result.toOption.get
    completion.content shouldBe "Hello world"
    completion.usage.isDefined shouldBe true
    completion.usage.get.promptTokens shouldBe 1
    completion.usage.get.completionTokens shouldBe 2
  }

  it should "fail with ValidationError when required text is missing" in withServer { exchange =>
    val body =
      """{
        |  "message": {
        |    "content": [
        |      { "type": "text" }
        |    ]
        |  }
        |}""".stripMargin

    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(200, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[ValidationError]
    result.left.toOption.get.message should include("Missing required text")
  }

  it should "map HTTP 401 to AuthenticationError" in withServer { exchange =>
    val body  = """{ "message": "nope" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(401, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[AuthenticationError]
  }

  it should "map HTTP 429 to RateLimitError" in withServer { exchange =>
    val body  = """{ "message": "slow down" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(429, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    result.left.toOption.get shouldBe a[RateLimitError]
  }

  it should "map HTTP 5xx to ServiceError" in withServer { exchange =>
    val body  = """{ "message": "boom" }"""
    val bytes = body.getBytes(StandardCharsets.UTF_8)
    exchange.getResponseHeaders.add("Content-Type", "application/json")
    exchange.sendResponseHeaders(500, bytes.length)
    val os = exchange.getResponseBody
    os.write(bytes)
    os.close()
  } { baseUrl =>
    val client = new CohereClient(config(baseUrl))

    val result = client.complete(conversation, CompletionOptions())
    result.isLeft shouldBe true
    val err = result.left.toOption.get
    err shouldBe a[ServiceError]
    err.context("httpStatus") shouldBe "500"
  }
}
