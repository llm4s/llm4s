package org.llm4s.llmconnect.middleware

import org.llm4s.error.RateLimitError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RateLimitingMiddlewareSpec extends AnyFlatSpec with Matchers {

  class NoOpClient extends LLMClient {
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def streamComplete(
      c: Conversation,
      o: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] =
      Right(Completion("id", 0L, "content", "model", AssistantMessage("content")))
    override def getContextWindow(): Int     = 100
    override def getReserveCompletion(): Int = 10
  }

  "RateLimitingMiddleware" should "allow requests within limit" in {
    // 60 requests per minute = 1 request per second
    val middleware = new RateLimitingMiddleware(60, 60)
    val client     = middleware.wrap(new NoOpClient)

    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isRight"))
  }

  it should "reject requests when bucket is empty" in {
    // 1 request per minute, burst 1
    val middleware = new RateLimitingMiddleware(1, 1)
    val client     = middleware.wrap(new NoOpClient)

    // First request consumes the only token
    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isRight"))

    // Second request should fail immediately (bucket empty)
    val result = client.complete(Conversation(Seq.empty))
    (result should be).a(Symbol("isLeft"))
    result.swap.getOrElse(fail("Expected Left")).shouldBe(a[RateLimitError])
  }

  it should "refill tokens over time" in {
    // 600 RPM = 10 requests per second = 1 token every 100ms
    val startTime  = System.nanoTime()
    var now        = startTime
    val timeSource = () => now

    val middleware = new RateLimitingMiddleware(600, 1, timeSource) // burst 1
    val client     = middleware.wrap(new NoOpClient)

    // Consume 1
    client.complete(Conversation(Seq.empty))

    // Immediate next should fail
    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isLeft"))

    // Advance time by 150ms (in nanos)
    now = startTime + 150_000_000L

    // Should succeed now
    (client.complete(Conversation(Seq.empty)) should be).a(Symbol("isRight"))
  }
}
