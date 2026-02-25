package org.llm4s.llmconnect.middleware

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.metrics.{ MetricsCollector, Outcome }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import scala.concurrent.duration.FiniteDuration
import java.util.concurrent.atomic.AtomicInteger

class MetricsMiddlewareSpec extends AnyFlatSpec with Matchers {

  class FakeMetricsCollector extends MetricsCollector {
    val requests = new AtomicInteger(0)
    val tokens   = new AtomicInteger(0)
    val costs    = new AtomicInteger(0)

    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      requests.incrementAndGet()

    override def addTokens(provider: String, model: String, input: Long, output: Long): Unit =
      tokens.addAndGet((input + output).toInt)

    override def recordCost(provider: String, model: String, cost: Double): Unit =
      costs.incrementAndGet()
  }

  class MockClient(costStub: Option[Double] = None) extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val usage = Some(TokenUsage(10, 20, 30))
      Right(
        Completion("id", 0L, "content", "model", AssistantMessage("content"), usage = usage, estimatedCost = costStub)
      )
    }

    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = ???
    override def getContextWindow(): Int     = 100
    override def getReserveCompletion(): Int = 10
  }

  "MetricsMiddleware" should "record metrics on success" in {
    val collector  = new FakeMetricsCollector()
    val middleware = new MetricsMiddleware(collector, "test-provider", "test-model")
    val client     = middleware.wrap(new MockClient(costStub = Some(0.002)))

    client.complete(Conversation(Seq.empty))

    collector.requests.get() shouldBe 1
    collector.tokens.get() shouldBe 30 // 10 input + 20 output
    collector.costs.get() shouldBe 1
  }

  it should "record metrics on failure" in {
    val collector = new FakeMetricsCollector()
    class FailingClient extends LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Left(org.llm4s.error.RateLimitError("Limit exceeded"))
      override def streamComplete(
        c: Conversation,
        o: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = ???
      override def getContextWindow(): Int     = 100
      override def getReserveCompletion(): Int = 10
    }

    val middleware = new MetricsMiddleware(collector, "test-provider", "test-model")
    val client     = middleware.wrap(new FailingClient())

    client.complete(Conversation(Seq.empty))

    collector.requests.get() shouldBe 1
    // Tokens and costs are only updated on success in the current implementation of MetricsMiddleware
    collector.tokens.get() shouldBe 0
    collector.costs.get() shouldBe 0
  }
}
