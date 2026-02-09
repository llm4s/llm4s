package org.llm4s.llmconnect.provider

import org.llm4s.error.{ AuthenticationError, RateLimitError }
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.metrics.{ ErrorKind, MockMetricsCollector }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for the MetricsRecording trait to ensure metrics are properly recorded
 * for successful operations, errors, token usage, and cost calculations.
 */
class MetricsRecordingSpec extends AnyFlatSpec with Matchers {

  // Test implementation of MetricsRecording - must be in same package to access protected method
  class TestMetricsClient(protected val metrics: org.llm4s.metrics.MetricsCollector) extends MetricsRecording {
    // Public wrapper to test the new withMetrics method (extractCost-based)
    def testWithMetrics[A](
      provider: String,
      model: String
    )(
      f: => Result[A]
    )(extractUsage: A => Option[TokenUsage], extractCost: A => Option[Double]): Result[A] =
      withMetrics(provider, model)(f)(extractUsage, extractCost)

    // Public wrapper to test the legacy withMetricsLegacy method (estimateCost-based)
    def testWithMetricsLegacy[A](
      provider: String,
      model: String
    )(
      f: => Result[A]
    )(extractUsage: A => Option[TokenUsage], estimateCost: TokenUsage => Option[Double]): Result[A] =
      withMetricsLegacy(provider, model)(f)(extractUsage, estimateCost)
  }

  "MetricsRecording.withMetrics" should "record successful request with duration" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    val result = client.testWithMetrics("test-provider", "test-model") {
      Right("success")
    }(_ => None, _ => None)

    result shouldBe Right("success")
    mockMetrics.totalRequests shouldBe 1
    mockMetrics.hasSuccessRequest("test-provider", "test-model") shouldBe true
  }

  it should "record error request with error kind" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    val result = client.testWithMetrics("test-provider", "test-model") {
      Left(AuthenticationError("openai", "Invalid API key"))
    }(_ => None, _ => None)

    result.isLeft shouldBe true
    mockMetrics.totalRequests shouldBe 1
    mockMetrics.hasErrorRequest("test-provider", ErrorKind.Authentication) shouldBe true
  }

  it should "record token usage for successful operations" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    case class TestResult(usage: TokenUsage)

    val result = client.testWithMetrics("test-provider", "test-model") {
      Right(TestResult(TokenUsage(promptTokens = 100, completionTokens = 50, totalTokens = 150)))
    }(r => Some(r.usage), _ => None)

    result.isRight shouldBe true
    mockMetrics.totalTokenCalls shouldBe 1

    val tokenCall = mockMetrics.tokenCalls.head
    tokenCall._1 shouldBe "test-provider"
    tokenCall._2 shouldBe "test-model"
    tokenCall._3 shouldBe 100 // input tokens
    tokenCall._4 shouldBe 50  // output tokens
  }

  it should "record cost when extractCost function is provided" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    case class TestResult(usage: TokenUsage, cost: Option[Double])

    val result = client.testWithMetrics("test-provider", "test-model") {
      Right(TestResult(TokenUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500), Some(0.015)))
    }(
      extractUsage = r => Some(r.usage),
      extractCost = r => r.cost // Extract cost from result
    )

    result.isRight shouldBe true
    mockMetrics.totalCostCalls shouldBe 1

    val costCall = mockMetrics.costCalls.head
    costCall._1 shouldBe "test-provider"
    costCall._2 shouldBe "test-model"
    costCall._3 shouldBe 0.015
  }

  it should "handle operations with no token usage" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    val result = client.testWithMetrics("test-provider", "test-model") {
      Right("success without tokens")
    }(_ => None, _ => None) // No token or cost extraction

    result.isRight shouldBe true
    mockMetrics.totalRequests shouldBe 1
    mockMetrics.totalTokenCalls shouldBe 0
    mockMetrics.totalCostCalls shouldBe 0
  }

  it should "record different error kinds correctly" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    // Authentication error
    client.testWithMetrics("provider1", "model1") {
      Left(AuthenticationError("openai", "Auth failed"))
    }(_ => None, _ => None)

    // Rate limit error
    client.testWithMetrics("provider2", "model2") {
      Left(RateLimitError("anthropic"))
    }(_ => None, _ => None)

    mockMetrics.totalRequests shouldBe 2
    mockMetrics.hasErrorRequest("provider1", ErrorKind.Authentication) shouldBe true
    mockMetrics.hasErrorRequest("provider2", ErrorKind.RateLimit) shouldBe true
  }

  it should "handle large token counts" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    case class TestResult(usage: TokenUsage)

    val result = client.testWithMetrics("test-provider", "test-model") {
      Right(TestResult(TokenUsage(promptTokens = 100000, completionTokens = 50000, totalTokens = 150000)))
    }(r => Some(r.usage), _ => None)

    result.isRight shouldBe true
    mockMetrics.totalTokenCalls shouldBe 1

    val tokenCall = mockMetrics.tokenCalls.head
    tokenCall._3 shouldBe 100000
    tokenCall._4 shouldBe 50000
  }

  "MetricsRecording.withMetricsLegacy" should "record cost when estimateCost function is provided (legacy)" in {
    val mockMetrics = new MockMetricsCollector()
    val client      = new TestMetricsClient(mockMetrics)

    case class TestResult(usage: TokenUsage)

    val result = client.testWithMetricsLegacy("test-provider", "test-model") {
      Right(TestResult(TokenUsage(promptTokens = 1000, completionTokens = 500, totalTokens = 1500)))
    }(
      extractUsage = r => Some(r.usage),
      estimateCost = _ => Some(0.015) // Compute cost from usage
    )

    result.isRight shouldBe true
    mockMetrics.totalCostCalls shouldBe 1

    val costCall = mockMetrics.costCalls.head
    costCall._1 shouldBe "test-provider"
    costCall._2 shouldBe "test-model"
    costCall._3 shouldBe 0.015
  }
}
