package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.config.OpenAIConfig
import org.llm4s.metrics.MockMetricsCollector

class OpenRouterClientSpec extends AnyFunSuite {

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
}
