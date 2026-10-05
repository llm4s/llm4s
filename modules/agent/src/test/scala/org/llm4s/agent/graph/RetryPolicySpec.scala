package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, LLMError, NetworkError, ValidationError }
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import scala.concurrent.duration.*

class RetryPolicySpec extends AnyFlatSpec with Matchers with EitherValues {

  private val recoverable: LLMError    = NetworkError("down", None, "https://example.test")
  private val nonRecoverable: LLMError = ValidationError("field", "bad")

  "RetryPolicy.none" should "make a single attempt" in {
    RetryPolicy.none.maxAttempts shouldBe 1
  }

  "RetryPolicy()" should "default to three attempts with a doubling backoff capped at five seconds" in {
    val policy = RetryPolicy()
    (policy.maxAttempts, policy.initialBackoff, policy.backoffFactor, policy.maxBackoff) shouldBe
      ((3, 100.millis, 2.0, 5.seconds))
  }

  "RetryPolicy.backoffAfter" should "multiply the wait by the factor after each failure, up to the cap" in {
    val policy = RetryPolicy(maxAttempts = 10, initialBackoff = 100.millis, backoffFactor = 2.0, maxBackoff = 1.second)

    (1 to 6).map(policy.backoffAfter) shouldBe
      Seq(100.millis, 200.millis, 400.millis, 800.millis, 1.second, 1.second)
  }

  it should "stay constant at a factor of one" in {
    val policy = RetryPolicy(initialBackoff = 250.millis, backoffFactor = 1.0)

    (1 to 4).map(policy.backoffAfter).distinct shouldBe Seq(250.millis)
  }

  it should "not wait when the initial backoff is zero" in {
    (1 to 3).map(RetryPolicy(initialBackoff = Duration.Zero).backoffAfter).distinct shouldBe Seq(Duration.Zero)
  }

  it should "settle at the cap instead of overflowing after very many failures" in {
    RetryPolicy(maxAttempts = 100000).backoffAfter(100000) shouldBe 5.seconds
  }

  "RetryPolicy.apply" should "reject a value that cannot work" in {
    an[IllegalArgumentException] should be thrownBy RetryPolicy(maxAttempts = 0)
    an[IllegalArgumentException] should be thrownBy RetryPolicy(initialBackoff = -1.millis)
    an[IllegalArgumentException] should be thrownBy RetryPolicy(backoffFactor = 0.5)
    an[IllegalArgumentException] should be thrownBy RetryPolicy(backoffFactor = Double.NaN)
    an[IllegalArgumentException] should be thrownBy RetryPolicy(backoffFactor = Double.PositiveInfinity)
    an[IllegalArgumentException] should be thrownBy RetryPolicy(initialBackoff = 2.seconds, maxBackoff = 1.second)
  }

  "RetryPolicy.of" should "return every problem at once as a ValidationError" in {
    val error = RetryPolicy.of(maxAttempts = 0, backoffFactor = 0.5).left.value

    error shouldBe a[ValidationError]
    error.message should include("maxAttempts")
    error.message should include("backoffFactor")
  }

  it should "return the policy when the values are valid" in {
    RetryPolicy.of(maxAttempts = 2).value.maxAttempts shouldBe 2
  }

  "The with* setters" should "change one field and validate it" in {
    val base = RetryPolicy.none

    val changed = base.withMaxAttempts(4).withInitialBackoff(10.millis).withBackoffFactor(3.0).withMaxBackoff(1.second)
    (changed.maxAttempts, changed.initialBackoff, changed.backoffFactor, changed.maxBackoff) shouldBe
      ((4, 10.millis, 3.0, 1.second))
    base.maxAttempts shouldBe 1
    an[IllegalArgumentException] should be thrownBy base.withMaxAttempts(0)
    an[IllegalArgumentException] should be thrownBy base.withMaxBackoff(Duration.Zero).withInitialBackoff(1.second)
  }

  "RetryPolicy.retries" should "retry a recoverable error only while attempts remain" in {
    val policy = RetryPolicy(maxAttempts = 3)

    policy.retries(recoverable, failedAttempts = 1) shouldBe true
    policy.retries(recoverable, failedAttempts = 2) shouldBe true
    policy.retries(recoverable, failedAttempts = 3) shouldBe false
  }

  it should "not retry a non-recoverable error by default" in {
    RetryPolicy(maxAttempts = 3).retries(nonRecoverable, failedAttempts = 1) shouldBe false
  }

  it should "follow a custom predicate" in {
    RetryPolicy(retryOn = _ => true).retries(nonRecoverable, failedAttempts = 1) shouldBe true
    RetryPolicy().withRetryOn(_ => false).retries(recoverable, failedAttempts = 1) shouldBe false
  }

  it should "never retry a cancellation, whatever the predicate says" in {
    RetryPolicy(retryOn = _ => true).retries(CancelledError("work"), failedAttempts = 1) shouldBe false
  }

  "A one-attempt policy" should "never retry" in {
    RetryPolicy.none.retries(recoverable, failedAttempts = 1) shouldBe false
  }
}
