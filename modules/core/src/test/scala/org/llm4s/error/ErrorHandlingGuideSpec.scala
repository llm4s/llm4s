package org.llm4s.error

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.{ Completion, Conversation, EmbeddingError }
import org.llm4s.model.ModelRegistryService
import org.llm4s.testutil.MockLLMClients.{ FailingMock, SimpleMock }
import org.llm4s.Result
import org.llm4s.types.{ OptionOps, Result, TryOps }
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
class ErrorHandlingGuideSpec extends AnyWordSpec with Matchers with EitherValues {

  override def nestedSuites: IndexedSeq[Suite] = Vector(new ResultSpec)

  // ---- 2. The basic pattern

  private def basicPattern(result: Result[Completion]): String =
    result match {
      case Right(completion) => s"Response: ${completion.content}"
      case Left(error)       => s"Error: ${error.message}"
    }

  // ---- 3. Chaining with for-comprehensions

  private def chained(): Result[String] =
    for {
      providerConfig <- Llm4sConfig.defaultProvider()
      registry       <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registry
      client       <- LLMConnect.getClient(providerConfig)
      conversation <- Conversation.userOnly("What is Scala?")
      completion   <- client.complete(conversation)
    } yield completion.content

  // ---- 5. Handling specific error types

  private def describe(result: Result[String]): String =
    result match {
      case Right(text)             => s"ok: $text"
      case Left(e: RateLimitError) => s"wait ${e.retryDelay.getOrElse(RateLimitError.DefaultRetryDelay)}, then retry"
      case Left(e: AuthenticationError) => s"fix the credentials for ${e.provider}"
      case Left(e: RecoverableError)    => s"transient, may succeed on retry: ${e.message}"
      case Left(e)                      => s"not retried (permanent, or not marked either way): ${e.message}"
    }

  // ---- 9. Recovering from failures: `client` and `conversation` are "built as in section 3"

  private val client = new SimpleMock("recovered")

  private def recovering() = ErrorRecovery.recoverWithBackoff(
    () => client.complete(conversation),
    maxAttempts = 3,
    baseDelay = 1.second
  )

  // ---- 6. Converting to exceptions

  private def orThrow[A](result: Result[A]): A =
    result.fold(error => throw new RuntimeException(error.formatted), identity)

  // ---- 8. Combining many Results

  private def parseAll(inputs: List[String]): Result[List[Int]] =
    Result.traverse(inputs)(s => Try(s.toInt).toResult)

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
      final case class VendorError(message: String)      extends LLMError with NonRecoverableError
      final case class FlakyVendorError(message: String) extends LLMError with RecoverableError
      describe(Left(VendorError("custom"))) shouldBe
        "not retried (permanent, or not marked either way): custom"
      describe(Left(FlakyVendorError("blip"))) shouldBe "transient, may succeed on retry: blip"
    }

    "route an error with no marker to the catch-all instead of throwing, which isRecoverable cannot promise" in {
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
    "follow the marker trait of each error type, as the guide's table says" in {
      val recoverable: List[LLMError] = List(
        NetworkError("m", None, "u"),
        RateLimitError("p"),
        TimeoutError("m", 1.second, "op"),
        ServiceError(503, "p", "d"),
        APIError("p", "m", None, None),
        ExecutionError("m", "op", None, None),
        SystemError("m", None),
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
    "run the guide's recoverWithBackoff call as written, with a client and a conversation" in {
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

      import org.llm4s.error.ThrowableOps._
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

    "combine independent Results into a tuple" in {
      Result.combine(Right(1), Right("a")) shouldBe Right((1, "a"))
      Result.combine(Right(1), Left(SimpleError("no")): Result[String]).isLeft shouldBe true
    }
  }

  "retrying" should {
    "retry a rate limit, give up with an ExecutionError, and not retry a permanent error" in {
      var attempts = 0
      val flaky: () => Result[String] = () => {
        attempts += 1
        if (attempts < 3) Left(RateLimitError("p", 1.millisecond)) else Right("done")
      }
      ErrorRecovery.recoverWithBackoff(flaky, maxAttempts = 5, baseDelay = 1.millisecond) shouldBe Right("done")
      attempts shouldBe 3

      var calls                           = 0
      val permanent: () => Result[String] = () => { calls += 1; Left(ValidationError("f", "r")) }
      ErrorRecovery.recoverWithBackoff(permanent, maxAttempts = 5, baseDelay = 1.millisecond).left.value shouldBe a[
        ValidationError
      ]
      calls shouldBe 1

      val alwaysLimited: () => Result[String] = () => Left(RateLimitError("p", 1.millisecond))
      ErrorRecovery
        .recoverWithBackoff(alwaysLimited, maxAttempts = 2, baseDelay = 1.millisecond)
        .left
        .value shouldBe a[ExecutionError]
    }

    "fail fast through a circuit breaker once it has opened" in {
      val breaker = new ErrorRecovery.CircuitBreaker[String](failureThreshold = 2, recoveryTimeout = 30.seconds)
      val failing = () => Left(NetworkError("down", None, "https://x")): Result[String]
      breaker.execute(failing).left.value shouldBe a[NetworkError]
      breaker.execute(failing).left.value shouldBe a[NetworkError]

      var reached = false
      val rejected = breaker.execute { () =>
        reached = true; Right("ok")
      }
      rejected.left.value shouldBe a[ServiceError]
      reached shouldBe false
    }
  }
}

/** Section 10's snippet, as the guide shows it; [[ErrorHandlingGuideSpec]] runs it as a nested suite. */
private class ResultSpec extends AnyWordSpec with Matchers with EitherValues {
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
}
