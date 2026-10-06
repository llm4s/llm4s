package org.llm4s.agent

import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.GraphError
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.error._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import upickle.default._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration._

/**
 * Verifies that an error raised by the LLM client reaches the caller of `Agent` as the very same
 * `LLMError` (same subtype, same fields) - the cause of the model node's `GraphError.NodeFailed` -
 * both directly and through a `ReliableClient`, and that the retry layer's recoverable /
 * non-recoverable classification decides how often it is retried.
 */
class AgentErrorPropagationSpec extends AnyFlatSpec with Matchers {

  final case class EchoResult(value: String)
  object EchoResult {
    implicit val rw: ReadWriter[EchoResult] = macroRW
  }

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

  /** The failed run's error: the model node's `NodeFailed`, whose cause is what the client returned. */
  private def runFailure(client: LLMClient): LLMError = plain(client).run("hello").error

  /** The error the client returned, unwrapped from the model node's failure. */
  private def runError(client: LLMClient): LLMError = {
    val failure = runFailure(client)
    failure shouldBe a[GraphError.NodeFailed]
    failure.asInstanceOf[GraphError.NodeFailed].nodeId.value shouldBe "assistant/model"
    cause(failure)
  }

  private val retries3: ReliabilityConfig =
    ReliabilityConfig.default.withRetryPolicy(RetryPolicy.fixedDelay(maxAttempts = 3, delay = Duration.Zero))

  private def reliable(inner: LLMClient, provider: String): ReliableClient =
    new ReliableClient(inner, provider, retries3, sleep = _ => ())

  "Agent.run" should "return the exact NetworkError the provider produced" in {
    val original = NetworkError("connection refused", None, "https://llm.example/v1")
    runError(new FailingLLMClient(original)) shouldBe theSameInstanceAs(original)
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

  /** Every concrete `LLMError` the library defines, with whether the default `RetryPolicy` retries it. */
  private def allErrors: Seq[(String, LLMError, Boolean)] = Seq(
    ("APIError", APIError("openai", "bad request", Some(400), Some("{}")), false),
    ("AuthenticationError", AuthenticationError("openai", "invalid key", "401"), false),
    ("CancelledError", CancelledError("op", None), false),
    ("ConfigurationError", ConfigurationError("missing key", List("OPENAI_API_KEY")), false),
    ("ContextError", ContextError.tokenBudgetExceeded(200, 100), false),
    ("ExecutionError", ExecutionError("failed", "run", Some(2)), false),
    ("InvalidInputError", InvalidInputError("field", "value", "reason"), false),
    ("NetworkError", NetworkError("refused", None, "https://llm.example/v1"), true),
    ("NotFoundError", NotFoundError("missing", "key-1"), false),
    ("OptimisticLockFailure", OptimisticLockFailure("conflict", "mem-1", 3L), false),
    ("ProcessingError", ProcessingError("op", "failed"), false),
    ("RateLimitError", RateLimitError("anthropic", 60.seconds), true),
    ("ServiceError 503", ServiceError(503, "p", "unavailable"), true),
    ("ServiceError 429", ServiceError(429, "p", "slow down"), true),
    ("ServiceError 408", ServiceError(408, "p", "request timeout"), true),
    ("ServiceError 400", ServiceError(400, "p", "bad request"), false),
    ("SimpleError", SimpleError("plain"), false),
    ("SystemError", SystemError("system"), false),
    ("TimeoutError", TimeoutError("slow", 5.seconds, "complete"), true),
    ("TokenizerError", TokenizerError.notFound("tok"), false),
    ("UnknownError", UnknownError("weird", new RuntimeException("x")), false),
    ("ValidationError", ValidationError("field", "reason"), false)
  )

  /** Fails its first `failures` calls with `error`, then answers with `reply`. */
  private class FlakyClient(failures: Int, error: LLMError, reply: String = "recovered") extends LLMClient {
    val calls = new AtomicInteger(0)

    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      if (calls.incrementAndGet() <= failures) Left(error) else Right(CompletionFixture.simple(reply))

    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)

    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  private def failingTool(name: String, handler: => Either[String, EchoResult]) = {
    val schema = Schema.`object`[Map[String, Any]]("Parameters").withRequiredField("input", Schema.string("Value"))
    ToolBuilder[Map[String, Any], EchoResult](name, "A tool that fails", schema)
      .withHandler(_ => handler)
      .buildSafe()
      .fold(e => fail(s"tool failed to build: $e"), identity)
  }

  /**
   * A cancellation is the run's own outcome, not a provider failure: a model call returning
   * `CancelledError` fails the run as cancelled, so it is checked apart from the rest.
   */
  private def providerErrors = allErrors.filterNot(_._2.isInstanceOf[CancelledError])

  "Every LLMError subtype" should "reach the caller of Agent.run as the identical instance" in {
    providerErrors.foreach { case (label, error, _) =>
      withClue(label)(runError(new FailingLLMClient(error)) shouldBe theSameInstanceAs(error))
    }
  }

  it should "reach the caller of Agent.continueConversation unchanged" in {
    providerErrors.foreach { case (label, error, _) =>
      withClue(label) {
        val client = new ScriptedLLMClient(Right(CompletionFixture.simple("earlier answer")), Left(error))
        val agent  = plain(client)
        val first  = agent.run("earlier question").value
        cause(agent.continueConversation(first, "follow-up").error) shouldBe theSameInstanceAs(error)
      }
    }
  }

  it should "be retried by ReliableClient exactly when the default retry policy says so, and returned unchanged" in {
    providerErrors.foreach { case (label, error, retried) =>
      withClue(label) {
        val inner = new CountingFailingClient(error)
        runError(reliable(inner, "p")) shouldBe error
        inner.calls.get() shouldBe (if (retried) 3 else 1)
      }
    }
  }

  "A CancelledError from the model" should "fail the run, never complete it" in {
    plain(new FailingLLMClient(CancelledError("op", None))).run("hello").isLeft shouldBe true
  }

  "Agent over ReliableClient" should "complete when a retry succeeds on the last attempt" in {
    val inner = new FlakyClient(failures = 2, ServiceError(503, "p", "unavailable"))

    val result = plain(reliable(inner, "p")).run("hello").value

    result.answer shouldBe Some("recovered")
    inner.calls.get() shouldBe 3
  }

  it should "return the last error once every attempt has failed" in {
    val error = ServiceError(503, "p", "still down")
    val inner = new FlakyClient(failures = 3, error)

    runError(reliable(inner, "p")) shouldBe error
    inner.calls.get() shouldBe 3
  }

  it should "fail fast with the circuit-breaker's ServiceError, without calling the model, once the circuit is open" in {
    val provider = ServiceError(503, "provider-x", "down")
    val inner    = new CountingFailingClient(provider)
    val config = ReliabilityConfig.default
      .withRetryPolicy(RetryPolicy.noRetry)
      .withCircuitBreaker(CircuitBreakerConfig(failureThreshold = 2))
    val client = new ReliableClient(inner, "provider-x", config, sleep = _ => ())

    runError(client) shouldBe provider
    runError(client) shouldBe provider
    inner.calls.get() shouldBe 2

    val open = runError(client)
    open shouldBe a[ServiceError]
    open.asInstanceOf[ServiceError].provider shouldBe "circuit-breaker"
    inner.calls.get() shouldBe 2
  }

  "A failing tool" should "not abort the run: the model sees the failure and the run completes" in {
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("flaky", ujson.Obj("input" -> "x"), "call-1"),
      CompletionFixture.simple("handled it")
    )

    val result = built(
      Agent
        .builder("assistant", client)
        .withTools(new ToolRegistry(Seq(failingTool("flaky", Left("backend unavailable")))))
    ).run("go").value

    result.answer shouldBe Some("handled it")
    val toolMessages = result.messages.collect { case m: ToolMessage => m }
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe "call-1"
    toolMessages.head.content should include("backend unavailable")
  }

  it should "also be survived when the handler throws" in {
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("explosive", ujson.Obj("input" -> "x"), "call-2"),
      CompletionFixture.simple("still here")
    )

    val result = built(
      Agent
        .builder("assistant", client)
        .withTools(new ToolRegistry(Seq(failingTool("explosive", throw new IllegalStateException("kaboom")))))
    ).run("go").value

    result.answer shouldBe Some("still here")
    result.messages.collect { case m: ToolMessage => m.content }.head should include("kaboom")
  }

  "An LLM error inside a handoff target" should "come back from Agent.run unchanged, from the target's model node" in {
    val targetError = NetworkError("specialist unreachable", None, "mock://specialist")
    val target      = Agent.builder("specialist", new CountingFailingClient(targetError))
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("handoff_to_specialist", ujson.Obj("reason" -> "needs expert"), "call-h")
    )

    val failure = built(Agent.builder("assistant", client).withHandoffs(Handoff.to("specialist", target, "Specialist")))
      .run("q")
      .error

    failure.asInstanceOf[GraphError.NodeFailed].nodeId.value shouldBe "specialist/model"
    cause(failure) shouldBe theSameInstanceAs(targetError)
  }

  "Running out of steps" should "be StepLimitReached inside Right, not an error" in {
    val client = new NTurnFakeLLMClient(CompletionFixture.withToolCall("missing_tool", ujson.Obj()))

    built(Agent.builder("assistant", client).withMaxSteps(2)).run("loop").value.status shouldBe
      AgentStatus.StepLimitReached
  }

  "A rejecting input guardrail" should "be a Blocked outcome, not an error, and never call the model" in {
    val client = new CountingFailingClient(UnknownError("must not be called", new RuntimeException("x")))
    val tooShort = new InputGuardrail {
      val name: String = "MinLength"
      def validate(value: String): Result[String] =
        if (value.length >= 50) Right(value)
        else Left(ValidationError.invalid("input", s"Input must be at least 50 chars; got ${value.length}"))
    }

    val result = built(Agent.builder("assistant", client).withMiddleware(new GuardrailMiddleware(Seq(tooShort), Nil)))
      .run("short")
      .value

    result.status match {
      case AgentStatus.Blocked(guardrail, reason) =>
        guardrail shouldBe "MinLength"
        reason should include("Input must be at least 50 chars; got 5")
      case other => fail(s"expected Blocked, got $other")
    }
    client.calls.get() shouldBe 0
  }
}
