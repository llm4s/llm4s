package org.llm4s.llmconnect.provider

import com.sun.net.httpserver.{ HttpExchange, HttpServer }
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.scalatest.OptionValues._
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.llmconnect.{ ProviderExchange, ProviderExchangeLogging, ProviderExchangeSink }
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, UserMessage }
import org.llm4s.metrics.MockMetricsCollector
import scala.collection.mutable.ListBuffer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

class AnthropicClientSpec extends AnyFunSuite with Matchers {

  private val testConfig = AnthropicConfig(
    apiKey = "test-key",
    model = "claude-3-5-sonnet-latest",
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  private def withServer(handler: HttpExchange => Unit)(test: String => Any): Unit = {
    val server = HttpServer.create(new InetSocketAddress("localhost", 0), 0)
    server.createContext("/v1/messages", exchange => handler(exchange))
    server.start()

    val baseUrl = s"http://localhost:${server.getAddress.getPort}"

    try
      test(baseUrl)
    finally
      server.stop(0)
  }

  test("anthropic client accepts custom metrics collector") {
    val mockMetrics = new MockMetricsCollector()
    val client      = new AnthropicClient(testConfig, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0)
  }

  test("anthropic client uses noop metrics by default") {
    val client = new AnthropicClient(testConfig)

    assert(client != null)
  }

  test("anthropic client returns correct context window") {
    val client = new AnthropicClient(testConfig)

    assert(client.getContextWindow() == 200000)
  }

  test("anthropic client returns correct reserve completion") {
    val client = new AnthropicClient(testConfig)

    assert(client.getReserveCompletion() == 4096)
  }

  test("anthropic client records provider exchanges when logging is enabled") {
    withServer { exchange =>
      val body =
        """{
          |  "id": "msg_test_123",
          |  "type": "message",
          |  "role": "assistant",
          |  "model": "claude-3-5-sonnet-latest",
          |  "content": [
          |    {
          |      "type": "text",
          |      "text": "Logged response"
          |    }
          |  ],
          |  "stop_reason": "end_turn",
          |  "stop_sequence": null,
          |  "usage": {
          |    "input_tokens": 8,
          |    "output_tokens": 4
          |  }
          |}""".stripMargin

      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.getResponseHeaders.add("Content-Type", "application/json")
      exchange.sendResponseHeaders(200, bytes.length)
      val os = exchange.getResponseBody
      os.write(bytes)
      os.close()
    } { baseUrl =>
      val exchanges = ListBuffer.empty[ProviderExchange]
      val sink = new ProviderExchangeSink {
        override def record(exchange: ProviderExchange): Unit =
          exchanges += exchange
      }
      val client = new AnthropicClient(
        testConfig.copy(baseUrl = baseUrl),
        exchangeLogging = ProviderExchangeLogging.Enabled(sink)
      )

      val result = client.complete(Conversation(Seq(UserMessage("hello"))), CompletionOptions())

      assert(result.isRight)
      exchanges should have size 1
      exchanges.head.provider shouldBe "anthropic"
      exchanges.head.model shouldBe Some("claude-3-5-sonnet-latest")
      exchanges.head.requestBody should include("hello")
      exchanges.head.responseBody.value should include("Logged response")
    }
  }
}
