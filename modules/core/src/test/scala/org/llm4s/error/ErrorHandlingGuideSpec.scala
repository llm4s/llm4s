package org.llm4s.error

import org.llm4s.testutil.GuideMirror
import org.llm4s.types.ProviderModelTypes.ProviderId

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.{ LLMClient, LLMConnect }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.model.ModelRegistryService
import org.llm4s.reliability.{ ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.testutil.MockLLMClients.{ FailingMock, SimpleMock }
import org.llm4s.types.Result
import org.llm4s.syntax.toResult
import org.scalatest.{ EitherValues, Suite }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._
import scala.util.Try

/**
 * The snippets of `docs/guide/error-handling.md`, compiled and run as written.
 *
 * If a snippet here stops compiling or its assertion fails, the guide is teaching something that
 * no longer works: change the guide and this spec together. Each `snippet` method below is the
 * code of one block in the guide; the assertions pin what the surrounding prose claims. Section 1 only
 * shows definitions, and section 10's snippet is the class [[ResultSpec]] below, run as a nested suite.
 */
@org.llm4s.tags.DocsSpec
class ErrorHandlingGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  "docs/guide/error-handling.md" should {
    "show every block this spec runs, as written" in {
      GuideMirror.missingFromPage(
        "docs/guide/error-handling.md",
        "modules/core/src/test/scala/org/llm4s/error/ErrorHandlingGuideSpec.scala"
      ) shouldBe empty
    }
  }

  override def nestedSuites: IndexedSeq[Suite] = Vector(new ResultSpec)

  // ---- 2. The basic pattern

  // guide-block
  def basicPattern(result: Result[Completion]): String =
    result match {
      case Right(completion) => s"Response: ${completion.content}"
      case Left(error)       => s"Error: ${error.message}"
    }
  // end-guide-block

  // ---- 3. Chaining with for-comprehensions

  // guide-block
  def chained(): Result[String] =
    for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client       <- LLMConnect.getClient(providerConfig)
      conversation <- Conversation.userOnly("What is Scala?")
      completion   <- client.complete(conversation)
    } yield completion.content
  // end-guide-block

  // ---- 5. Handling specific error types

  // guide-block
  def describe(result: Result[String]): String =
    result match {
      case Right(text) => s"ok: $text"
      case Left(e: RateLimitError) =>
        s"wait ${e.retryAfter.getOrElse(RetryPolicy.DefaultRateLimitDelay)}, then retry"
      case Left(e: AuthenticationError) => s"fix the credentials for ${e.provider}"
      case Left(e: ContextLengthError)  => s"shorten the conversation: ${e.message}"
      case Left(e) if e.isRetryable     => s"transient, may succeed on retry: ${e.message}"
      case Left(e)                      => s"not retried: ${e.message}"
    }
  // end-guide-block

  // ---- 9. Recovering from failures: `client` and `conversation` are "built as in section 3"

  private val client = new SimpleMock("recovered")

  private val reliable = ReliableClient(
    client,
    providerName = "openai",
    config = ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second))
  )

  private def recovering() = reliable.complete(conversation)

  /** A client whose every call is answered by `reply`, counting the calls. */
  final private class Scripted(reply: Int => Result[Completion]) extends LLMClient {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      reply(calls.incrementAndGet())
    override def streamComplete(conversation: Conversation, options: CompletionOptions)(
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def contextWindow: Int     = 4096
    override def reserveCompletion: Int = 1024
  }

  /** A `ReliableClient` over `scripted` that waits no time between attempts. */
  private def reliably(scripted: Scripted, config: ReliabilityConfig): ReliableClient =
    ReliableClient(scripted, "guide", config).withSleep(_ => ())

  private def retrying(maxAttempts: Int): ReliabilityConfig =
    ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = maxAttempts, baseDelay = 1.millis))

  // ---- 6. Converting to exceptions

  // guide-block
  def orThrow[A](result: Result[A]): A =
    result.fold(error => throw new RuntimeException(error.formatted), identity)
  // end-guide-block

  // ---- 8. Combining many Results

  // guide-block
  def parseAll(inputs: List[String]): Result[Seq[Int]] =
    Result.traverse(inputs)(s => Try(s.toInt).toResult)
  // end-guide-block

  private val conversation: Conversation = Conversation.userOnly("hi").value

  "the basic pattern" should {
    "print the text on success and the message on failure" in {
      basicPattern(new SimpleMock("Scala is a language").complete(conversation)) shouldBe
        "Response: Scala is a language"
      basicPattern(new FailingMock("connection refused").complete(conversation)) shouldBe
        "Error: connection refused"
    }
  }

  "the for-comprehension" should {
    "chain configuration, client creation, conversation and call, with no fixture to hand" in {
      // core's test configuration makes the keyless `fixturechat` provider the default
      chained().isRight shouldBe true
    }

    "stop at the first Left and skip the rest" in {
      var called = false
      val result = for {
        c <- Conversation.userOnly("")
        _ = { called = true }
        completion <- new SimpleMock("never").complete(c)
      } yield completion.content

      result.left.value shouldBe a[ValidationError]
      called shouldBe false
    }
  }

  "handling specific error types" should {
    "route each error to its own handling" in {
      describe(Right("hi")) shouldBe "ok: hi"
      describe(Left(RateLimitError(ProviderId("openai"), 5.seconds))) shouldBe "wait 5 seconds, then retry"
      describe(Left(RateLimitError(ProviderId("openai")))) shouldBe "wait 30 seconds, then retry"
      describe(Left(AuthenticationError(ProviderId("openai"), "bad key"))) shouldBe "fix the credentials for openai"
      describe(Left(NetworkError("down", None, "https://x"))) should startWith("transient")
      describe(Left(ValidationError("model", "must not be empty"))) should startWith("not retried")
    }

    "need a catch-all, because LLMError is an open trait (not sealed), and a custom error says if it is retryable" in {
      // guide-block
      final case class VendorError(message: String) extends LLMError

      final case class FlakyVendorError(message: String) extends LLMError {
        override def isRetryable: Boolean = true
      }
      // end-guide-block
      describe(Left(VendorError("custom"))) shouldBe "not retried: custom"
      describe(Left(FlakyVendorError("blip"))) shouldBe "transient, may succeed on retry: blip"
    }

    "route an embedding error by its status, as the guide's table of other modules' errors says" in {
      describe(Left(EmbeddingError(Some("500"), "provider failed", ProviderId("openai")))) shouldBe
        "transient, may succeed on retry: provider failed"
      describe(Left(EmbeddingError(Some("401"), "bad key", ProviderId("openai")))) shouldBe "not retried: bad key"
      describe(Left(EmbeddingError(None, "unparseable", ProviderId("openai")))) shouldBe "not retried: unparseable"
    }

    "tell a context-length overflow apart by its type" in {
      describe(Left(ContextLengthError(ProviderId("openai"), "too long"))) should startWith("shorten the conversation")
    }
  }

  "isRetryable" should {
    "answer for every error, one of your own included" in {
      NetworkError("down", None, "https://x").isRetryable shouldBe true
      ValidationError("f", "r").isRetryable shouldBe false

      final case class PlainError(message: String) extends LLMError
      PlainError("says nothing").isRetryable shouldBe false
    }

    "be what the guide's table says for each error type" in {
      val retryable: List[LLMError] = List(
        NetworkError("m", None, "u"),
        RateLimitError(ProviderId("p")),
        TimeoutError("m", 1.second, "op"),
        ServiceError(503, ProviderId("p"), "d"),
        ExecutionError("m", "op", None, None)
      )
      val not: List[LLMError] = List(
        AuthenticationError(ProviderId("p"), "d"),
        ConfigurationError("m", List("k")),
        ValidationError("f", "r"),
        ContextLengthError(ProviderId("p"), "d"),
        ModelNotFoundError(ProviderId("p"), "d"),
        InvalidInputError("f", "v", "r"),
        ProcessingError("op", "m"),
        NotFoundError("m", "k"),
        CancelledError("op"),
        SimpleError("m"),
        OptimisticLockFailure("m", "id", 1L),
        CircuitOpenError.open(ProviderId("p"), 1.second)
      )
      retryable.map(_.isRetryable).distinct shouldBe List(true)
      not.map(_.isRetryable).distinct shouldBe List(false)
    }

    "follow a ServiceError's status" in {
      ServiceError(503, ProviderId("p"), "d").isRetryable shouldBe true
      ServiceError(429, ProviderId("p"), "d").isRetryable shouldBe true
      ServiceError(408, ProviderId("p"), "d").isRetryable shouldBe true
      ServiceError(404, ProviderId("p"), "d").isRetryable shouldBe false
    }

    "report only the provider's own delay as retryAfter, the 30-second default being the retry policy's" in {
      RateLimitError(ProviderId("p"), 7.seconds).retryAfter shouldBe Some(7.seconds)
      RateLimitError(ProviderId("p")).retryAfter shouldBe None
      RetryPolicy.DefaultRateLimitDelay shouldBe 30.seconds
    }

    "report the transport facts of a mapped response on every error type" in {
      import org.llm4s.llmconnect.provider.HttpErrorMapper
      val error = HttpErrorMapper.toError(400, """{"error":{"message":"bad"}}""", "p", Map("x-request-id" -> Seq("r1")))
      error shouldBe a[ValidationError]
      error.httpStatus shouldBe Some(400)
      error.requestId shouldBe Some("r1")
      error.retryAfter shouldBe None
    }
  }

  "recovering from failures" should {
    "run the guide's ReliableClient call as written, with a client and a conversation" in {
      recovering().map(_.content) shouldBe Right("recovered")
    }
  }

  "converting to exceptions" should {
    "throw with the formatted message, and return the value on success" in {
      orThrow(Right(42)) shouldBe 42
      val thrown =
        intercept[RuntimeException](orThrow(Left(ConfigurationError("no provider", List("llm4s.providers")))))
      thrown.getMessage should include("ConfigurationError")
      thrown.getMessage should include("no provider")
    }

    "lose the error with getOrElse, which is why the guide recommends fold" in {
      val failed: Result[Int] = Left(ConfigurationError("no provider", List("llm4s.defaultProvider")))
      failed.getOrElse(0) shouldBe 0
      intercept[RuntimeException](failed.getOrElse(throw new RuntimeException("LLM call failed"))).getMessage shouldBe
        "LLM call failed"
    }
  }

  "converting exceptions into errors" should {
    "map a Try through toResult and a Throwable through toLLMError" in {
      Try("123".toInt).toResult shouldBe Right(123)
      Try("abc".toInt).toResult.left.value shouldBe a[LLMError]

      import org.llm4s.syntax.toLLMError
      new IllegalStateException("boom").toLLMError shouldBe a[LLMError]
      new InterruptedException("stop").toLLMError shouldBe a[CancelledError]
      intercept[InterruptedException](Try(throw new InterruptedException("stop")).toResult)
    }

    "capture a throwing block with Result.safely" in {
      Result.safely(1 / 1) shouldBe Right(1)
      Result.safely(1 / 0).isLeft shouldBe true
    }

    "turn an Option into a Result with the error to use when it is empty" in {
      Option.empty[String].toResult(NotFoundError("no such key", "model")).left.value shouldBe a[NotFoundError]
      Some("x").toResult(NotFoundError("no such key", "model")) shouldBe Right("x")
    }
  }

  "combining Results" should {
    "traverse a list, stopping at the first failure" in {
      parseAll(List("1", "2", "3")) shouldBe Right(List(1, 2, 3))
      parseAll(List("1", "x", "y")).isLeft shouldBe true
    }

    "collect every failure with validateAll" in {
      val checked = Result.validateAll(List("a", "", "b", ""))(s =>
        if (s.isEmpty) Left(ValidationError("item", "must not be empty")) else Right(s)
      )
      checked.left.value should have size 2
      Result.validateAll(List("a", "b"))(Right(_)) shouldBe Right(List("a", "b"))
    }
  }

  "retrying" should {
    "retry a rate limit, give up with the last error unchanged, and not retry a permanent error" in {
      val limited = RateLimitError(ProviderId("p"), 1.millisecond)
      val flaky   = new Scripted(call => if (call < 3) Left(limited) else new SimpleMock("done").complete(conversation))
      reliably(flaky, retrying(5)).complete(conversation).map(_.content) shouldBe Right("done")
      flaky.calls.get() shouldBe 3

      val permanent = new Scripted(_ => Left(ValidationError("f", "r")))
      reliably(permanent, retrying(5)).complete(conversation).left.value shouldBe a[ValidationError]
      permanent.calls.get() shouldBe 1

      val alwaysLimited = new Scripted(_ => Left(limited))
      reliably(alwaysLimited, retrying(2)).complete(conversation) shouldBe Left(limited)
      alwaysLimited.calls.get() shouldBe 2
    }

    "retry a network error, but not a client-error status or an optimistic-lock failure, as section 4 says" in {
      def callsFor(error: LLMError): Int = {
        val failing = new Scripted(_ => Left(error))
        reliably(failing, retrying(3)).complete(conversation) shouldBe Left(error)
        failing.calls.get()
      }
      callsFor(NetworkError("down", None, "https://x")) shouldBe 3
      callsFor(ServiceError(404, ProviderId("p"), "d")) shouldBe 1
      callsFor(OptimisticLockFailure("m", "id", 1L)) shouldBe 1
    }

    "wait a rate limit's own delay and a service error's hint, and the policy's delay otherwise" in {
      val policy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second, maxDelay = 3.seconds)
      policy.delayFor(1, RateLimitError(ProviderId("p"), 7.seconds)) shouldBe 7.seconds
      policy.delayFor(1, RateLimitError(ProviderId("p"))) shouldBe RetryPolicy.DefaultRateLimitDelay
      policy.delayFor(1, ServiceError(503, ProviderId("p"), "d").withRetryAfter(9.seconds)) shouldBe 9.seconds
      Seq(1, 2, 3)
        .map(policy.delayFor(_, ServiceError(503, ProviderId("p"), "d"))) shouldBe Seq(1.second, 2.seconds, 3.seconds)
      policy.isRetryable(NetworkError("down", None, "https://x")) shouldBe true
      policy.isRetryable(ValidationError("f", "r")) shouldBe false
    }

    "end with a TimeoutError once the deadline has passed" in {
      val now     = new java.util.concurrent.atomic.AtomicReference(java.time.Instant.parse("2026-01-01T00:00:00Z"))
      val failing = new Scripted(_ => Left(NetworkError("down", None, "https://x")))
      val client = ReliableClient(
        failing,
        "guide",
        retrying(5).withDeadline(1.second)
      ).withClock(() => now.get()).withSleep(_ => now.updateAndGet(_.plusSeconds(2)): Unit)
      client.complete(conversation).left.value shouldBe a[TimeoutError]
    }

    "fail fast through the circuit breaker once it has opened, with a CircuitOpenError that is not retried" in {
      val config = ReliabilityConfig(
        retryPolicy = RetryPolicy.noRetry,
        circuitBreaker = org.llm4s.reliability.CircuitBreakerConfig(failureThreshold = 2, recoveryTimeout = 30.seconds)
      )
      val failing = new Scripted(_ => Left(NetworkError("down", None, "https://x")))
      val breaker = reliably(failing, config)
      breaker.complete(conversation).left.value shouldBe a[NetworkError]
      breaker.complete(conversation).left.value shouldBe a[NetworkError]

      val rejected = breaker.complete(conversation).left.value
      rejected shouldBe a[CircuitOpenError]
      failing.calls.get() shouldBe 2
      RetryPolicy.exponentialBackoff().isRetryable(rejected) shouldBe false
    }
  }
}

/** Section 10's snippet, as the guide shows it; [[ErrorHandlingGuideSpec]] runs it as a nested suite. */
private class ResultSpec extends AnyWordSpec with Matchers with EitherValues {
  // guide-block
  "a Result" should {
    "unwrap with EitherValues, and check the error type" in {
      val ok: Result[String]     = Right("expected")
      val failed: Result[String] = Left(ValidationError("model", "empty"))

      ok.value shouldBe "expected"
      ok.map(_.toUpperCase) shouldBe Right("EXPECTED")
      failed.left.value shouldBe a[ValidationError]
      failed.left.value.message should include("empty")
    }
  }
  // end-guide-block
}
