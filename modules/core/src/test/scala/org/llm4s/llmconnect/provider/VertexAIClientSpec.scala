package org.llm4s.llmconnect.provider

import org.scalatest.funsuite.AnyFunSuite
import org.llm4s.llmconnect.config.VertexAIConfig
import org.llm4s.metrics.MockMetricsCollector
import org.llm4s.model.ModelRegistryService

class VertexAIClientSpec extends AnyFunSuite {

  private given ModelRegistryService = org.llm4s.model.ModelRegistryTestSupport.defaultService()

  private val testConfig = VertexAIConfig(
    projectId = "my-gcp-project",
    location = "us-central1",
    model = "gemini-2.0-flash",
    credentialFilePath = None,
    contextWindow = 1048576,
    reserveCompletion = 8192
  )

  test("vertex ai client accepts custom metrics collector") {
    val mockMetrics = new MockMetricsCollector()
    val client      = new VertexAIClient(testConfig, mockMetrics)

    assert(client != null)
    assert(mockMetrics.totalRequests == 0)
  }

  test("vertex ai client uses noop metrics by default") {
    val client = new VertexAIClient(testConfig)

    assert(client != null)
  }

  test("vertex ai client returns correct context window") {
    val client = new VertexAIClient(testConfig)

    assert(client.getContextWindow() == 1048576)
  }

  test("vertex ai client returns correct reserve completion") {
    val client = new VertexAIClient(testConfig)

    assert(client.getReserveCompletion() == 8192)
  }

  test("vertex ai client computes correct base URL from location") {
    val config = testConfig.copy(location = "europe-west4")

    assert(config.computedBaseUrl == "https://europe-west4-aiplatform.googleapis.com/v1")
  }

  test("vertex ai config defaults to us-central1 location") {
    assert(VertexAIConfig.DEFAULT_LOCATION == "us-central1")
  }

  test("vertex ai config computes us-central1 base URL") {
    assert(testConfig.computedBaseUrl == "https://us-central1-aiplatform.googleapis.com/v1")
  }
}
