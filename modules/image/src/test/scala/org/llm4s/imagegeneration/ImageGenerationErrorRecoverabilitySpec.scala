package org.llm4s.imagegeneration

import org.llm4s.error.{ LLMError, NonRecoverableError, RecoverableError }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An `ImageGenerationError` is an `LLMError`, and `LLMError.isRecoverable` (with `recoverableErrors`,
 * `nonRecoverableErrors` and every retry policy built on them) is `true` only for a [[RecoverableError]]: an error
 * with neither marker is reported as not recoverable. So every case says which it is, with a marker, and a
 * transient one is not silently left out of retries.
 */
class ImageGenerationErrorRecoverabilitySpec extends AnyFlatSpec with Matchers {

  /** What each case should answer. Exhaustive on purpose: a new `ImageGenerationError` does not compile until it is classified here. */
  private def retryable(error: ImageGenerationError): Boolean = error match {
    case _: ImageRateLimitError => true
    case s: ImageServiceError   => ImageServiceError.isTransientStatus(s.statusCode)
    case _: ImageAuthenticationError | _: ImageValidationError | _: InvalidPromptError | _: InsufficientResourcesError |
        _: UnsupportedOperation | _: ImageUnknownError =>
      false
  }

  private val everyCase: Seq[ImageGenerationError] = Seq(
    ImageAuthenticationError("bad key"),
    ImageRateLimitError("slow down"),
    ImageServiceError("provider is down", 503),
    ImageServiceError("provider refused", 400),
    ImageValidationError("size not supported"),
    InvalidPromptError("prompt rejected"),
    InsufficientResourcesError("out of credits"),
    UnsupportedOperation("no edits here"),
    ImageUnknownError(new RuntimeException("something else"))
  )

  "LLMError.isRecoverable" should "answer for every ImageGenerationError as its marker says" in {
    everyCase.foreach(error => withClue(error.toString)(LLMError.isRecoverable(error) shouldBe retryable(error)))
  }

  it should "call a rate limit recoverable and a rejected request, credential or prompt not" in {
    LLMError.isRecoverable(ImageRateLimitError("slow down")) shouldBe true
    LLMError.isRecoverable(ImageAuthenticationError("bad key")) shouldBe false
    LLMError.isRecoverable(ImageValidationError("bad size")) shouldBe false
    LLMError.isRecoverable(InvalidPromptError("bad prompt")) shouldBe false
    LLMError.isRecoverable(UnsupportedOperation("no edits")) shouldBe false
    LLMError.isRecoverable(InsufficientResourcesError("no credits")) shouldBe false
    LLMError.isRecoverable(ImageUnknownError(new RuntimeException("?"))) shouldBe false
  }

  it should "judge an ImageServiceError by the status the provider answered" in {
    Seq(0, 408, 429, 500, 502, 503, 504, 599).foreach { status =>
      withClue(s"status $status")(LLMError.isRecoverable(ImageServiceError("down", status)) shouldBe true)
    }
    Seq(400, 401, 403, 404, 409, 422).foreach { status =>
      withClue(s"status $status")(LLMError.isRecoverable(ImageServiceError("refused", status)) shouldBe false)
    }
  }

  "recoverableErrors and nonRecoverableErrors" should "partition image errors instead of throwing" in {
    val errors = everyCase.toList

    LLMError.recoverableErrors(errors).map(_.getClass.getSimpleName).toSet shouldBe
      Set("ImageRateLimitError", "TransientImageServiceError")
    LLMError.nonRecoverableErrors(errors) should have size (errors.size - 2).toLong
  }

  "ImageServiceError" should "still be built and matched as (message, status)" in {
    val transient: ImageServiceError = ImageServiceError("down", 503)
    val rejected: ImageServiceError  = ImageServiceError("refused", 403)

    transient should matchPattern { case ImageServiceError("down", 503) => }
    rejected should matchPattern { case ImageServiceError("refused", 403) => }
    transient.statusCode shouldBe 503
    transient.code shouldBe Some("503")
    rejected.code shouldBe Some("403")
  }

  it should "compare by what it says, whichever case it is" in {
    ImageServiceError("down", 503) shouldBe ImageServiceError("down", 503)
    ImageServiceError("down", 503) should not be ImageServiceError("down", 502)
    ImageServiceError("refused", 403) shouldBe ImageServiceError("refused", 403)
  }

  it should "carry the marker that matches its status" in {
    ImageServiceError("down", 503) shouldBe a[RecoverableError]
    ImageServiceError("refused", 403) shouldBe a[NonRecoverableError]
  }
}
