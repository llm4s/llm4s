package org.llm4s.imagegeneration

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * An `ImageGenerationError` is an `LLMError`, and `LLMError.isRetryable` (which every retry policy reads) is `false`
 * unless an error says otherwise. So every transient case overrides it, and is not silently left out of retries.
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

  "LLMError.isRetryable" should "answer for every ImageGenerationError" in {
    everyCase.foreach(error => withClue(error.toString)(error.isRetryable shouldBe retryable(error)))
  }

  it should "call a rate limit retryable and a rejected request, credential or prompt not" in {
    ImageRateLimitError("slow down").isRetryable shouldBe true
    ImageAuthenticationError("bad key").isRetryable shouldBe false
    ImageValidationError("bad size").isRetryable shouldBe false
    InvalidPromptError("bad prompt").isRetryable shouldBe false
    UnsupportedOperation("no edits").isRetryable shouldBe false
    InsufficientResourcesError("no credits").isRetryable shouldBe false
    (ImageUnknownError(new RuntimeException("?"))).isRetryable shouldBe false
  }

  it should "judge an ImageServiceError by the status the provider answered" in {
    Seq(0, 408, 429, 500, 502, 503, 504, 599).foreach { status =>
      withClue(s"status $status")(ImageServiceError("down", status).isRetryable shouldBe true)
    }
    Seq(400, 401, 403, 404, 409, 422).foreach { status =>
      withClue(s"status $status")(ImageServiceError("refused", status).isRetryable shouldBe false)
    }
  }

  "isRetryable" should "partition image errors" in {
    val errors = everyCase.toList

    errors.filter(_.isRetryable).map(_.getClass.getSimpleName).toSet shouldBe
      Set("ImageRateLimitError", "TransientImageServiceError")
    errors.filterNot(_.isRetryable) should have size (errors.size - 2).toLong
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

  it should "be retryable as its status says" in {
    ImageServiceError("down", 503).isRetryable shouldBe true
    ImageServiceError("refused", 403).isRetryable shouldBe false
  }
}
