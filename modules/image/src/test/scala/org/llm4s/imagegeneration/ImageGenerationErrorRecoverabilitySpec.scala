package org.llm4s.imagegeneration

import org.llm4s.error.{ LLMError, NonRecoverableError, RecoverableError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An `ImageGenerationError` is an `LLMError`, and `LLMError.isRecoverable` (with `recoverableErrors`,
 * `nonRecoverableErrors` and every retry policy built on them) matches only [[RecoverableError]] and
 * [[NonRecoverableError]]: an error with neither marker throws a `MatchError` there. So every case must say
 * whether trying again can help.
 */
class ImageGenerationErrorRecoverabilitySpec extends AnyFlatSpec with Matchers {

  /** What each case should answer. Exhaustive on purpose: a new `ImageGenerationError` does not compile until it is classified here. */
  private def retryable(error: ImageGenerationError): Boolean = error match {
    case _: RateLimitError => true
    case s: ServiceError   => ServiceError.isTransientStatus(s.statusCode)
    case _: AuthenticationError | _: ValidationError | _: InvalidPromptError | _: InsufficientResourcesError |
        _: UnsupportedOperation | _: UnknownError =>
      false
  }

  private val everyCase: Seq[ImageGenerationError] = Seq(
    AuthenticationError("bad key"),
    RateLimitError("slow down"),
    ServiceError("provider is down", 503),
    ServiceError("provider refused", 400),
    ValidationError("size not supported"),
    InvalidPromptError("prompt rejected"),
    InsufficientResourcesError("out of credits"),
    UnsupportedOperation("no edits here"),
    UnknownError(new RuntimeException("something else"))
  )

  "LLMError.isRecoverable" should "answer for every ImageGenerationError, and not throw a MatchError" in {
    everyCase.foreach(error => withClue(error.toString)(LLMError.isRecoverable(error) shouldBe retryable(error)))
  }

  it should "call a rate limit recoverable and a rejected request, credential or prompt not" in {
    LLMError.isRecoverable(RateLimitError("slow down")) shouldBe true
    LLMError.isRecoverable(AuthenticationError("bad key")) shouldBe false
    LLMError.isRecoverable(ValidationError("bad size")) shouldBe false
    LLMError.isRecoverable(InvalidPromptError("bad prompt")) shouldBe false
    LLMError.isRecoverable(UnsupportedOperation("no edits")) shouldBe false
    LLMError.isRecoverable(InsufficientResourcesError("no credits")) shouldBe false
    LLMError.isRecoverable(UnknownError(new RuntimeException("?"))) shouldBe false
  }

  it should "judge a ServiceError by the status the provider answered" in {
    Seq(0, 408, 429, 500, 502, 503, 504, 599).foreach { status =>
      withClue(s"status $status")(LLMError.isRecoverable(ServiceError("down", status)) shouldBe true)
    }
    Seq(400, 401, 403, 404, 409, 422).foreach { status =>
      withClue(s"status $status")(LLMError.isRecoverable(ServiceError("refused", status)) shouldBe false)
    }
  }

  "recoverableErrors and nonRecoverableErrors" should "partition image errors instead of throwing" in {
    val errors = everyCase.toList

    LLMError.recoverableErrors(errors).map(_.getClass.getSimpleName).toSet shouldBe
      Set("RateLimitError", "TransientServiceError")
    LLMError.nonRecoverableErrors(errors) should have size (errors.size - 2).toLong
  }

  "ServiceError" should "still be built and matched as (message, status)" in {
    val transient: ServiceError = ServiceError("down", 503)
    val rejected: ServiceError  = ServiceError("refused", 403)

    transient should matchPattern { case ServiceError("down", 503) => }
    rejected should matchPattern { case ServiceError("refused", 403) => }
    transient.statusCode shouldBe 503
    transient.code shouldBe Some("503")
    rejected.code shouldBe Some("403")
  }

  it should "compare by what it says, whichever case it is" in {
    ServiceError("down", 503) shouldBe ServiceError("down", 503)
    ServiceError("down", 503) should not be ServiceError("down", 502)
    ServiceError("refused", 403) shouldBe ServiceError("refused", 403)
  }

  it should "carry the marker that matches its status" in {
    ServiceError("down", 503) shouldBe a[RecoverableError]
    ServiceError("refused", 403) shouldBe a[NonRecoverableError]
  }
}
