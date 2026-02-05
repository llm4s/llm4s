package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.config.GeminiConfig
import org.llm4s.metrics.MockMetricsCollector

class GeminiClientSpec extends AnyFunSuite {

  private val testConfig = GeminiConfig(
    apiKey = "test-key",
    model = "gemini-2.0-flash-exp",
    baseUrl = "https://generativelanguage.googleapis.com",
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  test("gemini client accepts custom metrics collector") {
    val mockMetrics = new MockMetricsCollector()
    val client      = new GeminiClient(testConfig, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0)
  }

  test("gemini client uses noop metrics by default") {
    val client = new GeminiClient(testConfig)

    assert(client != null)
  }

  test("gemini client returns correct context window") {
    val client = new GeminiClient(testConfig)

    assert(client.getContextWindow() == 1048576)
  }

  test("gemini client returns correct reserve completion") {
    val client = new GeminiClient(testConfig)

    assert(client.getReserveCompletion() == 8192)
  }
}
