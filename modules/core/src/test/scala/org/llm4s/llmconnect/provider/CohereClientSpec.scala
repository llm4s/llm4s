package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.config.CohereConfig
import org.llm4s.metrics.MockMetricsCollector

class CohereClientSpec extends AnyFunSuite {

  private val testConfig = CohereConfig(
    apiKey = "test-key",
    model = "command-r-plus",
    baseUrl = "https://api.cohere.ai",
    contextWindow = 128000,
    reserveCompletion = 4096
  )

  test("cohere client accepts custom metrics collector") {
    val mockMetrics = new MockMetricsCollector()
    val client      = new CohereClient(testConfig, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0)
  }

  test("cohere client uses noop metrics by default") {
    val client = new CohereClient(testConfig)

    assert(client != null)
  }

  test("cohere client returns correct context window") {
    val client = new CohereClient(testConfig)

    assert(client.getContextWindow() == 128000)
  }

  test("cohere client returns correct reserve completion") {
    val client = new CohereClient(testConfig)

    assert(client.getReserveCompletion() == 4096)
  }
}
