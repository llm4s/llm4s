package org.llm4s.error

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
 * The snippets of `docs/guide/error-handling.md`, run as written (`DocSnippets` compiles the page).
 *
 * Each `// snippet:` region is part of one of the guide's Scala blocks word for word, and the last test reads the page
 * and fails unless every region is on it and every block of it is run or listed as not run (`GuideSnippets`, #2833),
 * so the page and this spec cannot drift. The assertions pin what the surrounding prose claims. Section 10's snippet
 * is the class [[ResultSpec]] below, run as a nested suite. The regions sit between `format: off` lines, so scalafmt
 * keeps the page's layout.
 */
@org.llm4s.tags.DocsSpec
class ErrorHandlingGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  override def nestedSuites: IndexedSeq[Suite] = Vector(new ResultSpec)

  // format: off

  // ---- 2. The basic pattern

  // snippet: basic-pattern
  def basicPattern(result: Result[Completion]): String =
    result match {
      case Right(completion) => s"Response: ${completion.content}"
      case Left(error)       => s"Error: ${error.message}"
    }
  // end snippet

  // ---- 3. Chaining with for-comprehensions

  // snippet: chained
  def chained(): Result[String] =
    for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client       <- LLMConnect.getClient(providerConfig)
      conversation <- Conversation.userOnly("What is Scala?")
      completion   <- client.complete(conversation)
    } yield completion.content
  // end snippet

  // ---- 5. Handling specific error types

  // snippet: describe
  def describe(result: Result[String]): String =
    result match {
      case Right(text) => s"ok: $text"
      case Left(e: RateLimitError) =>
        s"wait ${e.retryDelay.getOrElse(RateLimitError.DefaultRetryDelay)}, then retry"
      case Left(e: AuthenticationError) => s"fix the credentials for ${e.provider}"
      case Left(e: RecoverableError)    => s"transient, may succeed on retry: ${e.message}"
      case Left(e)                      => s"not retried (permanent, or not marked either way): ${e.message}"
    }
  // end snippet

  // ---- 9. Recovering from failures: `client` and `conversation` are "built as in section 3"

  private val client = new SimpleMock("recovered")

  // snippet: reliable
  val reliable = ReliableClient(
    client,
    providerName = "openai",
    config = ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second))
  )
  // end snippet

  private def recovering() = reliable.complete(conversation)

  /** A client whose every call is answered by `reply`, counting the calls. */
  final private class Scripted(reply: Int => Result[Completion]) extends LLMClient {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      reply(calls.incrementAndGet())
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  /** A `ReliableClient` over `scripted` that waits no time between attempts. */
  private def reliably(scripted: Scripted, config: ReliabilityConfig): ReliableClient =
    ReliableClient(scripted, "guide", config).withSleep(_ => ())

  private def retrying(maxAttempts: Int): ReliabilityConfig =
    ReliabilityConfig(retryPolicy = RetryPolicy.exponentialBackoff(maxAttempts = maxAttempts, baseDelay = 1.millis))

  // ---- 6. Converting to exceptions

  // snippet: or-throw
  def orThrow[A](result: Result[A]): A =
    result.fold(error => throw new RuntimeException(error.formatted), identity)
  // end snippet

  // ---- 8. Combining many Results

  // snippet: parse-all
  def parseAll(inputs: List[String]): Result[List[Int]] =
    Result.traverse(inputs)(s => Try(s.toInt).toResult)
  // end snippet

  // format: on

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
      describe(Left(RateLimitError("openai", 5.seconds))) shouldBe "wait 5 seconds, then retry"
      describe(Left(RateLimitError("openai"))) shouldBe "wait 30 seconds, then retry"
      describe(Left(AuthenticationError("openai", "bad key"))) shouldBe "fix the credentials for openai"
      describe(Left(NetworkError("down", None, "https://x"))) should startWith("transient")
      describe(Left(ValidationError("model", "must not be empty"))) should startWith("not retried")
    }

    "need a catch-all, because LLMError is an open trait (not sealed), and a custom error mixes in a marker" in {
      // snippet: vendor-error
      final case class VendorError(message: String) extends LLMError with NonRecoverableError
      // end snippet
      final case class FlakyVendorError(message: String) extends LLMError with RecoverableError
      describe(Left(VendorError("custom"))) shouldBe
        "not retried (permanent, or not marked either way): custom"
      describe(Left(FlakyVendorError("blip"))) shouldBe "transient, may succeed on retry: blip"
    }

    "route an error with no marker to the catch-all" in {
      // The guide lists the library errors that carry neither marker. If one of these gains a marker,
      // update the guide's table of errors defined by other modules together with this assertion.
      val embedding = EmbeddingError(Some("500"), "provider failed", "openai")
      (embedding: LLMError) should not be a[RecoverableError]
      (embedding: LLMError) should not be a[NonRecoverableError]
      describe(Left(embedding)) shouldBe "not retried (permanent, or not marked either way): provider failed"

      final case class UnmarkedError(message: String) extends LLMError
      describe(Left(UnmarkedError("no marker"))) shouldBe
        "not retried (permanent, or not marked either way): no marker"
    }
  }

  "recoverability" should {
    "be answered by isRecoverable for every error, as the Basic Usage guide calls it, an unmarked one included" in {
      LLMError.isRecoverable(NetworkError("down", None, "https://x")) shouldBe true
      LLMError.isRecoverable(ValidationError("f", "r")) shouldBe false
      LLMError.isRecoverable(EmbeddingError(Some("500"), "provider failed", "openai")) shouldBe false

      final case class UnmarkedError(message: String) extends LLMError
      LLMError.isRecoverable(UnmarkedError("no marker")) shouldBe false
    }

    "agree with the marker-trait match the guide shows, including on an unmarked error" in {
      def retryable(error: LLMError): Boolean = error match {
        case _: RecoverableError => true
        case _                   => false
      }
      final case class UnmarkedError(message: String) extends LLMError
      val errors: List[LLMError] = List(
        NetworkError("down", None, "https://x"),
        ValidationError("f", "r"),
        EmbeddingError(Some("500"), "provider failed", "openai"),
        UnmarkedError("no marker")
      )
      errors.foreach(e => withClue(e.toString)(LLMError.isRecoverable(e) shouldBe retryable(e)))
    }

    "partition a list holding an unmarked error instead of throwing" in {
      final case class UnmarkedError(message: String) extends LLMError
      val network                = NetworkError("down", None, "https://x")
      val unmarked               = UnmarkedError("no marker")
      val errors: List[LLMError] = List(network, unmarked, ValidationError("f", "r"))
      LLMError.recoverableErrors(errors) shouldBe List(network)
      LLMError.nonRecoverableErrors(errors) should have size 2
      LLMError.nonRecoverableErrors(errors) should contain(unmarked)
    }

    "follow the marker trait of each error type, as the guide's table says" in {
      val recoverable: List[LLMError] = List(
        NetworkError("m", None, "u"),
        RateLimitError("p"),
        TimeoutError("m", 1.second, "op"),
        ServiceError(503, "p", "d"),
        ExecutionError("m", "op", None, None),
        OptimisticLockFailure("m", "id", 1L)
      )
      val permanent: List[LLMError] = List(
        AuthenticationError("p", "d"),
        ConfigurationError("m", List("k")),
        ValidationError("f", "r"),
        InvalidInputError("f", "v", "r"),
        ProcessingError("op", "m"),
        NotFoundError("m", "k"),
        CancelledError("op"),
        SimpleError("m")
      )
      recoverable.map(LLMError.isRecoverable).distinct shouldBe List(true)
      permanent.map(LLMError.isRecoverable).distinct shouldBe List(false)
    }

    "say whether a ServiceError's own status is transient, with no import" in {
      ServiceError(503, "p", "d").isRecoverableStatus shouldBe true
      ServiceError(429, "p", "d").isRecoverableStatus shouldBe true
      ServiceError(408, "p", "d").isRecoverableStatus shouldBe true
      ServiceError(404, "p", "d").isRecoverableStatus shouldBe false
    }

    "give a rate limit's own delay, falling back to the default of 30 seconds" in {
      RateLimitError("p", 7.seconds).retryDelay shouldBe Some(7.seconds)
      RateLimitError("p").retryDelay shouldBe Some(RateLimitError.DefaultRetryDelay)
      RateLimitError.DefaultRetryDelay shouldBe 30.seconds
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
      val failed: Result[Int] = Left(ConfigurationError("no provider", List("llm4s.providers.provider")))
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
      val limited = RateLimitError("p", 1.millisecond)
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
      callsFor(ServiceError(404, "p", "d")) shouldBe 1
      callsFor(OptimisticLockFailure("m", "id", 1L)) shouldBe 1
    }

    "wait a rate limit's own delay and a service error's hint, and the policy's delay otherwise" in {
      val policy = RetryPolicy.exponentialBackoff(maxAttempts = 3, baseDelay = 1.second, maxDelay = 3.seconds)
      policy.delayFor(1, RateLimitError("p", 7.seconds)) shouldBe 7.seconds
      policy.delayFor(1, RateLimitError("p")) shouldBe RateLimitError.DefaultRetryDelay
      policy.delayFor(1, ServiceError(503, "p", "d").withRetryAfter(9.seconds)) shouldBe 9.seconds
      Seq(1, 2, 3).map(policy.delayFor(_, ServiceError(503, "p", "d"))) shouldBe Seq(1.second, 2.seconds, 3.seconds)
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

  "the guide" should {
    "show exactly the code this spec runs" in {
      org.llm4s.testutil.GuideSnippets.check(
        "docs/guide/error-handling.md",
        Seq("modules/core/src/test/scala/org/llm4s/error/ErrorHandlingGuideSpec.scala"),
        notRun = Set(
          "type Result[+A] = Either[LLMError, A]", // section 1: the definition, not code to run
          "import org.llm4s.syntax.toResult // Try(...).toResult; or org.llm4s.syntax.* for all the extensions", // imports
          // expressions shown with their results; "converting exceptions into errors" runs each with an assertion
          "import org.llm4s.error.NotFoundError",
          "import org.llm4s.error.{ ConfigurationError, NotFoundError, ValidationError }" // constructors only
        )
      ) shouldBe empty
    }
  }
}

/** Section 10's snippet, as the guide shows it; [[ErrorHandlingGuideSpec]] runs it as a nested suite. */
private class ResultSpec extends AnyWordSpec with Matchers with EitherValues {
  // snippet: result-spec
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
  // end snippet
}
