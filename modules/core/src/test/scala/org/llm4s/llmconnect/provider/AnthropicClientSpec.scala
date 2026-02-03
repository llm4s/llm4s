package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.config.AnthropicConfig
import org.llm4s.metrics.MockMetricsCollector

class AnthropicClientSpec extends AnyFunSuite {

  private val testConfig = AnthropicConfig(
    apiKey = "test-key",
    model = "claude-3-5-sonnet-latest",
    baseUrl = "https://api.anthropic.com",
    contextWindow = 200000,
    reserveCompletion = 4096
  )

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
}
