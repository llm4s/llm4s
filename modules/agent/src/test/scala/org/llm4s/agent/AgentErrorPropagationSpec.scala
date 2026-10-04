package org.llm4s.agent

import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.collection.mutable.ListBuffer
import scala.concurrent.duration._

/**
 * Verifies that an error raised by the LLM client reaches the caller of `Agent` as the very same
 * `LLMError` (same subtype, same fields), both directly and through a `ReliableClient`, and that
 * the retry layer's recoverable / non-recoverable classification decides how often it is retried.
 *
 * Existing specs assert only that the result is a `Left`, or its class; these assert the identity
 * and fields of the error, so an agent or retry layer that wrapped or rewrote it would fail.
 */
class AgentErrorPropagationSpec extends AnyFlatSpec with Matchers {

  /** Always fails with `error` and counts how many times the model was called. */
  private class CountingFailingClient(error: LLMError) extends LLMClient {
    val calls = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls.incrementAndGet()
      Left(error)
    }

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def runError(client: LLMClient): LLMError =
    new Agent(client).run("hello", org.llm4s.toolapi.ToolRegistry.empty).fold(identity, s => fail(s"expected Left: $s"))

  private val retries3: ReliabilityConfig =
    ReliabilityConfig.default.withRetryPolicy(RetryPolicy.fixedDelay(maxAttempts = 3, delay = Duration.Zero))

  private def reliable(inner: LLMClient, provider: String): ReliableClient =
    new ReliableClient(inner, provider, retries3, sleep = _ => ())

  "Agent.run" should "return the exact NetworkError the provider produced" in {
    val original = NetworkError("connection refused", None, "https://llm.example/v1")
    runError(new FailingLLMClient(original)) shouldBe original
  }

  it should "return the exact RateLimitError, keeping provider and retryAfter" in {
    val original = RateLimitError("anthropic", 60.seconds)
    val error    = runError(new FailingLLMClient(original))
    error shouldBe original
    error.asInstanceOf[RateLimitError].retryAfter shouldBe Some(60.seconds)
  }

  it should "return the exact AuthenticationError and ServiceError" in {
    val auth    = AuthenticationError("openai", "invalid key", "401")
    val service = ServiceError(503, "openai", "unavailable")
    runError(new FailingLLMClient(auth)) shouldBe auth
    runError(new FailingLLMClient(service)) shouldBe service
  }

  it should "return the provider error unchanged when it occurs after a successful tool step" in {
    val original = ServiceError(502, "provider-x", "bad gateway")
    val calls    = new AtomicInteger(0)
    val failSecond = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        if (calls.getAndIncrement() == 0) Right(CompletionFixture.withToolCall("missing_tool", ujson.Obj()))
        else Left(original)
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }

    runError(failSecond) shouldBe original
    calls.get() shouldBe 2
  }

  it should "surface the guardrail's ValidationError with its rejection reason, and never call the model" in {
    val client = new CountingFailingClient(UnknownError("must not be called", new RuntimeException("x")))
    val tooShort = new InputGuardrail {
      val name: String = "MinLength"
      def validate(value: String): Result[String] =
        if (value.length >= 50) Right(value)
        else Left(ValidationError.invalid("input", s"Input must be at least 50 chars; got ${value.length}"))
    }

    val error = new Agent(client)
      .run("short", org.llm4s.toolapi.ToolRegistry.empty, inputGuardrails = Seq(tooShort))
      .fold(identity, s => fail(s"expected Left: $s"))

    error shouldBe a[ValidationError]
    error.message should include("Input must be at least 50 chars; got 5")
    LLMError.isRecoverable(error) shouldBe false
    client.calls.get() shouldBe 0
  }

  "Agent.runWithEvents" should "emit AgentFailed carrying the original error" in {
    val original = NetworkError("down", None, "mock://llm")
    val events   = ListBuffer[AgentEvent]()
    val result =
      new Agent(new FailingLLMClient(original))
        .runWithEvents("hello", org.llm4s.toolapi.ToolRegistry.empty, onEvent = events += _)

    result shouldBe Left(original)
    events.collect { case f: AgentEvent.AgentFailed => f.error } shouldBe Seq(original)
  }

  "Agent over ReliableClient" should "retry a recoverable RateLimitError maxAttempts times, then return it unchanged" in {
    val original = RateLimitError("retry-provider", 5.seconds)
    val inner    = new CountingFailingClient(original)

    runError(reliable(inner, "retry-provider")) shouldBe original
    inner.calls.get() shouldBe 3
  }

  it should "not retry a non-recoverable AuthenticationError and return it unchanged" in {
    val original = AuthenticationError("openai", "invalid key", "401")
    val inner    = new CountingFailingClient(original)

    runError(reliable(inner, "openai")) shouldBe original
    inner.calls.get() shouldBe 1
  }
}
