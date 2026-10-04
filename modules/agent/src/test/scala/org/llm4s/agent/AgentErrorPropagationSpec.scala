package org.llm4s.agent

import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.streaming.AgentEvent
import org.llm4s.error._
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import upickle.default._
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

  // ---------------------------------------------------------------------------------------------
  // Additional coverage: every LLMError subtype, every Agent entry point, retry boundaries,
  // tool / handoff / step-limit / guardrail paths
  // ---------------------------------------------------------------------------------------------

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

  private def completeState: AgentState = AgentStateFixture.complete("earlier question", "earlier answer")

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

  "Every LLMError subtype" should "reach the caller of Agent.run as the identical instance" in {
    allErrors.foreach { case (label, error, _) =>
      withClue(label)(runError(new FailingLLMClient(error)) shouldBe error)
    }
  }

  it should "reach the caller of Agent.runWithEvents unchanged and be the payload of AgentFailed" in {
    allErrors.foreach { case (label, error, _) =>
      withClue(label) {
        val events = ListBuffer[AgentEvent]()
        val result = new Agent(new FailingLLMClient(error))
          .runWithEvents("hello", org.llm4s.toolapi.ToolRegistry.empty, onEvent = events += _)

        result shouldBe Left(error)
        events.collect { case f: AgentEvent.AgentFailed => f.error }.toList shouldBe List(error)
      }
    }
  }

  it should "reach the caller of Agent.continueConversation unchanged" in {
    allErrors.foreach { case (label, error, _) =>
      withClue(label) {
        new Agent(new FailingLLMClient(error)).continueConversation(completeState, "follow-up") shouldBe Left(error)
      }
    }
  }

  it should "reach the caller of Agent.runWithStrategy unchanged" in {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    allErrors.foreach { case (label, error, _) =>
      withClue(label) {
        new Agent(new FailingLLMClient(error))
          .runWithStrategy("hello", org.llm4s.toolapi.ToolRegistry.empty) shouldBe Left(error)
      }
    }
  }

  it should "be retried by ReliableClient exactly when the default retry policy says so, and returned unchanged" in {
    allErrors.foreach { case (label, error, retried) =>
      withClue(label) {
        val inner = new CountingFailingClient(error)
        runError(reliable(inner, "p")) shouldBe error
        inner.calls.get() shouldBe (if (retried) 3 else 1)
      }
    }
  }

  "Agent over ReliableClient" should "complete when a retry succeeds on the last attempt" in {
    val inner = new FlakyClient(failures = 2, ServiceError(503, "p", "unavailable"))

    val state = new Agent(reliable(inner, "p"))
      .run("hello", org.llm4s.toolapi.ToolRegistry.empty)
      .fold(e => fail(s"expected success: $e"), identity)

    state.status shouldBe AgentStatus.Complete
    state.conversation.messages.last.content shouldBe "recovered"
    inner.calls.get() shouldBe 3
  }

  it should "return the last error once every attempt has failed" in {
    val error = ServiceError(503, "p", "still down")
    val inner = new FlakyClient(failures = 3, error)

    runError(reliable(inner, "p")) shouldBe error
    inner.calls.get() shouldBe 3
  }

  it should "retry the streaming path the same way" in {
    val inner  = new FlakyClient(failures = 2, RateLimitError("p", 1.second))
    val events = ListBuffer[AgentEvent]()

    val result = new Agent(reliable(inner, "p"))
      .runWithEvents("hello", org.llm4s.toolapi.ToolRegistry.empty, onEvent = events += _)

    result.isRight shouldBe true
    inner.calls.get() shouldBe 3
    events.exists(_.isInstanceOf[AgentEvent.AgentFailed]) shouldBe false
    events.exists(_.isInstanceOf[AgentEvent.AgentCompleted]) shouldBe true
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

    val state = new Agent(client)
      .run("go", new ToolRegistry(Seq(failingTool("flaky", Left("backend unavailable")))))
      .fold(e => fail(s"a tool failure must not become a Left: $e"), identity)

    state.status shouldBe AgentStatus.Complete
    val toolMessages = state.conversation.messages.collect { case m: ToolMessage => m }
    toolMessages should have size 1
    toolMessages.head.toolCallId shouldBe "call-1"
    toolMessages.head.content should include("backend unavailable")
  }

  it should "also be survived when the handler throws" in {
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall("explosive", ujson.Obj("input" -> "x"), "call-2"),
      CompletionFixture.simple("still here")
    )

    val state = new Agent(client)
      .run("go", new ToolRegistry(Seq(failingTool("explosive", throw new IllegalStateException("kaboom")))))
      .fold(e => fail(s"a throwing tool must not become a Left: $e"), identity)

    state.status shouldBe AgentStatus.Complete
    state.conversation.messages.collect { case m: ToolMessage => m.content }.head should include("kaboom")
  }

  "An LLM error inside a handoff target" should "come back from Agent.run unchanged" in {
    val targetError = NetworkError("specialist unreachable", None, "mock://specialist")
    val target      = new Agent(new CountingFailingClient(targetError))
    val handoff     = Handoff.to("specialist", target, "Specialist")
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "needs expert"), "call-h")
    )

    new Agent(client).run("q", org.llm4s.toolapi.ToolRegistry.empty, handoffs = Seq(handoff)) shouldBe Left(targetError)
  }

  it should "come back from Agent.runWithEvents unchanged, with HandoffCompleted(success = false)" in {
    val targetError = RateLimitError("specialist", 5.seconds)
    val target      = new Agent(new CountingFailingClient(targetError))
    val handoff     = Handoff.to("specialist", target, "Specialist")
    val client = new NTurnFakeLLMClient(
      CompletionFixture.withToolCall(handoff.handoffId, ujson.Obj("reason" -> "needs expert"), "call-h")
    )
    val events = ListBuffer[AgentEvent]()

    val result = new Agent(client)
      .runWithEvents("q", org.llm4s.toolapi.ToolRegistry.empty, onEvent = events += _, handoffs = Seq(handoff))

    result shouldBe Left(targetError)
    events.collect { case e: AgentEvent.HandoffCompleted => e.success }.toList shouldBe List(false)
  }

  "Running out of steps" should "be a Failed state inside Right, not an error" in {
    val client = new NTurnFakeLLMClient(CompletionFixture.withToolCall("missing_tool", ujson.Obj()))

    val state = new Agent(client)
      .run("loop", org.llm4s.toolapi.ToolRegistry.empty, maxSteps = Some(2))
      .fold(e => fail(s"step limit must not be a Left: $e"), identity)

    state.status shouldBe AgentStatus.Failed("Maximum step limit reached")
  }

  "A rejecting output guardrail" should "return its reason even after a successful tool round, having called the model twice" in {
    val calls = new AtomicInteger(0)
    val client = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        Right(
          if (calls.getAndIncrement() == 0) CompletionFixture.withToolCall("missing_tool", ujson.Obj())
          else CompletionFixture.simple("leaks the secret")
        )
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      override def getContextWindow(): Int     = 4096
      override def getReserveCompletion(): Int = 1024
    }
    val noSecrets = new OutputGuardrail {
      val name: String = "NoSecrets"
      def validate(value: String): Result[String] =
        if (value.contains("secret")) Left(ValidationError.invalid("output", "response must not contain secrets"))
        else Right(value)
    }

    val error = new Agent(client)
      .run("q", org.llm4s.toolapi.ToolRegistry.empty, outputGuardrails = Seq(noSecrets))
      .fold(identity, s => fail(s"expected Left: $s"))

    error shouldBe a[ValidationError]
    error.message should include("response must not contain secrets")
    LLMError.isRecoverable(error) shouldBe false
    calls.get() shouldBe 2
  }

  "A rejecting input guardrail" should "stop every entry point before the model is called" in {
    implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
    val client = new CountingFailingClient(UnknownError("must not be called", new RuntimeException("x")))
    val agent  = new Agent(client)
    val reject = new InputGuardrail {
      val name: String = "Reject"
      def validate(value: String): Result[String] =
        Left(ValidationError.invalid("input", "not allowed"))
    }

    val results = Seq(
      agent.run("q", org.llm4s.toolapi.ToolRegistry.empty, inputGuardrails = Seq(reject)),
      agent.runWithEvents("q", org.llm4s.toolapi.ToolRegistry.empty, _ => (), inputGuardrails = Seq(reject)),
      agent.runWithStrategy("q", org.llm4s.toolapi.ToolRegistry.empty, inputGuardrails = Seq(reject)),
      agent.continueConversation(completeState, "q", inputGuardrails = Seq(reject)),
      agent.continueConversationWithEvents(completeState, "q", _ => (), inputGuardrails = Seq(reject))
    )

    results.foreach { r =>
      val error = r.fold(identity, s => fail(s"expected Left: $s"))
      error shouldBe a[ValidationError]
      error.message should include("not allowed")
    }
    client.calls.get() shouldBe 0
  }

  "Agent.continueConversation" should "refuse an unfinished state with a ValidationError and never call the model" in {
    val client = new CountingFailingClient(UnknownError("must not be called", new RuntimeException("x")))
    val unfinished = AgentState(
      conversation = Conversation(Seq(UserMessage("q"))),
      tools = org.llm4s.toolapi.ToolRegistry.empty,
      status = AgentStatus.InProgress
    )

    val error = new Agent(client)
      .continueConversation(unfinished, "more")
      .fold(identity, s => fail(s"expected Left: $s"))

    error shouldBe a[ValidationError]
    client.calls.get() shouldBe 0
  }
}
