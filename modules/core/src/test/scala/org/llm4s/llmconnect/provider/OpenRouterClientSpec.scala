package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, ToolMessage }
import org.llm4s.metrics.MockMetricsCollector

class OpenRouterClientSpec extends AnyFunSuite with Matchers {

  private val testConfig = OpenAIConfig(
    apiKey = "test-key",
    model = "anthropic/claude-3.5-sonnet",
    organization = None,
    baseUrl = "https://openrouter.ai/api/v1",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

  test("openrouter client accepts custom metrics collector") {
    val mockMetrics = new MockMetricsCollector()
    val client      = new OpenRouterClient(testConfig, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0)
  }

  test("openrouter client uses noop metrics by default") {
    val client = new OpenRouterClient(testConfig)

    assert(client != null)
  }

  test("openrouter client returns correct context window") {
    val client = new OpenRouterClient(testConfig)

    assert(client.getContextWindow() == 200000)
  }

  test("openrouter client returns correct reserve completion") {
    val client = new OpenRouterClient(testConfig)

    assert(client.getReserveCompletion() == 4096)
  }

  test("openrouter client serializes tool message with correct fields") {
    val client       = new OpenRouterClientTestHelper(testConfig)
    val conversation = Conversation(Seq(ToolMessage("tool-output", "call-42")))

    val requestBody = client.exposedCreateRequestBody(conversation, CompletionOptions())
    val toolMsg     = requestBody("messages")(0)

    toolMsg("role").str shouldBe "tool"
    toolMsg("tool_call_id").str shouldBe "call-42"
    toolMsg("content").str shouldBe "tool-output"
  }
}

final private class OpenRouterClientTestHelper(cfg: OpenAIConfig) extends OpenRouterClient(cfg) {
  def exposedCreateRequestBody(conversation: Conversation, options: CompletionOptions): ujson.Obj =
    createRequestBody(conversation, options)
}
