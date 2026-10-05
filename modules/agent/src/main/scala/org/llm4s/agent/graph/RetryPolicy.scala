package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How a node is run again after its own failure, inside the run.
 *
 * A node that throws, or returns [[NodeResult.Fail]], is run again against the same committed
 * snapshot while its error passes `retryOn` and attempts remain. By default only a recoverable
 * error is retried (`LLMError.isRecoverable`). A cancellation, a result the kernel rejects (an
 * undeclared write, an invalid route) and a suspension are never retried.
 *
 * The wait after the n-th failed attempt is `initialBackoff * backoffFactor^(n-1)`, at most
 * `maxBackoff`. It is an interruptible wait: cancelling the run, or its deadline expiring, ends
 * the retries. The attempts belong to one run: `recover` runs a failed task again with its full
 * policy.
 *
 * Every value is validated on the way in: `apply` and the `with*` setters throw
 * `IllegalArgumentException`; [[RetryPolicy.of]] returns a `ValidationError` for untrusted input.
 *
 * @param maxAttempts the most times the node is run, the first included; `1` means no retry
 * @param initialBackoff the wait after the first failed attempt
 * @param backoffFactor what each further wait is multiplied by; at least `1.0`
 * @param maxBackoff the longest wait
 * @param retryOn whether an error is worth another attempt
 */
final case class RetryPolicy private (
  maxAttempts: Int,
  initialBackoff: FiniteDuration,
  backoffFactor: Double,
  maxBackoff: FiniteDuration,
  retryOn: LLMError => Boolean
):
  def withMaxAttempts(n: Int): RetryPolicy = RetryPolicy(n, initialBackoff, backoffFactor, maxBackoff, retryOn)
  def withInitialBackoff(d: FiniteDuration): RetryPolicy =
    RetryPolicy(maxAttempts, d, backoffFactor, maxBackoff, retryOn)
  def withBackoffFactor(f: Double): RetryPolicy =
    RetryPolicy(maxAttempts, initialBackoff, f, maxBackoff, retryOn)
  def withMaxBackoff(d: FiniteDuration): RetryPolicy =
    RetryPolicy(maxAttempts, initialBackoff, backoffFactor, d, retryOn)
  def withRetryOn(predicate: LLMError => Boolean): RetryPolicy =
    RetryPolicy(maxAttempts, initialBackoff, backoffFactor, maxBackoff, predicate)

  /** The wait after `failedAttempts` attempts have failed: `failedAttempts = 1` after the first. */
  def backoffAfter(failedAttempts: Int): FiniteDuration =
    val nanos = initialBackoff.toNanos.toDouble * math.pow(backoffFactor, (failedAttempts - 1).toDouble)
    if nanos >= maxBackoff.toNanos.toDouble then maxBackoff else FiniteDuration(nanos.toLong, NANOSECONDS)

  /** Whether the node runs again after `failedAttempts` failed attempts that ended in `error`. */
  private[graph] def retries(error: LLMError, failedAttempts: Int): Boolean =
    failedAttempts < maxAttempts && !error.isInstanceOf[CancelledError] && retryOn(error)

object RetryPolicy:

  /** Retry only what the error says can be retried: `LLMError.isRecoverable`. */
  val recoverableOnly: LLMError => Boolean = error => LLMError.isRecoverable(error)

  private def problems(
    maxAttempts: Int,
    initialBackoff: FiniteDuration,
    backoffFactor: Double,
    maxBackoff: FiniteDuration
  ): List[String] =
    List(
      Option.when(maxAttempts < 1)(s"maxAttempts must be at least 1, was $maxAttempts"),
      Option.when(initialBackoff < Duration.Zero)(s"initialBackoff must not be negative, was $initialBackoff"),
      Option.when(backoffFactor.isNaN || backoffFactor.isInfinite || backoffFactor < 1.0)(
        s"backoffFactor must be a finite number of at least 1.0, was $backoffFactor"
      ),
      Option.when(maxBackoff < initialBackoff)(
        s"maxBackoff ($maxBackoff) must not be less than initialBackoff ($initialBackoff)"
      )
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid value; use [[of]] for untrusted input. */
  def apply(
    maxAttempts: Int = 3,
    initialBackoff: FiniteDuration = 100.millis,
    backoffFactor: Double = 2.0,
    maxBackoff: FiniteDuration = 5.seconds,
    retryOn: LLMError => Boolean = recoverableOnly
  ): RetryPolicy =
    val found = problems(maxAttempts, initialBackoff, backoffFactor, maxBackoff)
    require(found.isEmpty, found.mkString("; "))
    new RetryPolicy(maxAttempts, initialBackoff, backoffFactor, maxBackoff, retryOn)

  def of(
    maxAttempts: Int = 3,
    initialBackoff: FiniteDuration = 100.millis,
    backoffFactor: Double = 2.0,
    maxBackoff: FiniteDuration = 5.seconds,
    retryOn: LLMError => Boolean = recoverableOnly
  ): Result[RetryPolicy] =
    problems(maxAttempts, initialBackoff, backoffFactor, maxBackoff) match
      case Nil   => Right(new RetryPolicy(maxAttempts, initialBackoff, backoffFactor, maxBackoff, retryOn))
      case found => Left(ValidationError("retryPolicy", found))

  /** One attempt: a failed node fails the run. The default for every node. */
  val none: RetryPolicy = apply(maxAttempts = 1)
