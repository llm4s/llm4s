package org.llm4s.jev

import org.llm4s.error.{ CancelledError, LLMError, RateLimitError, ServiceError }
import org.llm4s.reliability.RetryPolicy
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.concurrent.duration.*

/**
 * Runs an operation under a [[JevRetryPolicy]].
 *
 * Whether a failure is retried is [[org.llm4s.reliability.RetryPolicy.isTransient]], LLM4S's one rule. The wait
 * before a retry is the delay the server asked for when it asked for one (the error's `retryAfter`), else the
 * policy's exponential backoff with jitter. The whole call is bounded by the policy's budget: when the wait would
 * reach what is left of it, the last error is returned instead. The last error is returned as it is, so its type
 * says why the call failed.
 *
 * The clock, the sleep and the random source are parameters so a test runs it without waiting.
 *
 * @param nanoTime a monotonic clock in nanoseconds
 * @param sleep    pauses the calling thread; an interrupt during a pause ends the call with a [[CancelledError]]
 * @param random   a random number in `[0, 1)`
 */
final private[jev] class JevRetry(
  policy: JevRetryPolicy,
  nanoTime: () => Long = () => System.nanoTime(),
  sleep: FiniteDuration => Unit = delay => Thread.sleep(delay.toMillis),
  random: () => Double = () => java.util.concurrent.ThreadLocalRandom.current().nextDouble()
) {

  private val logger = LoggerFactory.getLogger(getClass)

  /** The computed backoff after attempt `attempt` (1-based) failed: doubled each time, capped, then jittered. */
  def backoff(attempt: Int): FiniteDuration = {
    val doubled = policy.backoffInitial.toNanos.toDouble * math.pow(2.0, (attempt - 1).toDouble)
    val capped  = math.min(doubled, policy.backoffMax.toNanos.toDouble)
    FiniteDuration((capped * (1.0 - policy.jitter * random())).toLong, NANOSECONDS)
  }

  private def serverHint(error: LLMError): Option[FiniteDuration] = error match {
    case rate: RateLimitError  => rate.retryAfter
    case service: ServiceError => service.retryAfter
    case _                     => None
  }

  /** Runs `operation`, retrying a transient failure as the policy allows. */
  def run[A](operation: () => Result[A]): Result[A] = {
    val start = nanoTime()

    @tailrec def attempt(number: Int): Result[A] =
      CancelledError.attempt("jev")(operation()) match {
        case success @ Right(_) => success
        case failure @ Left(error) =>
          if (number > policy.maxRetries || !RetryPolicy.isTransient(error)) failure
          else {
            val delay   = serverHint(error).getOrElse(backoff(number))
            val elapsed = FiniteDuration(nanoTime() - start, NANOSECONDS)
            if (elapsed + delay >= policy.budget) {
              logger.debug("Jev call out of retry budget after {} attempt(s)", number)
              failure
            } else {
              logger.debug("Jev attempt {} failed with {}; retrying in {}", number, error.getClass.getSimpleName, delay)
              CancelledError.catchInterrupt(sleep(delay)) match {
                case Right(()) => attempt(number + 1)
                case Left(interrupted) =>
                  Thread.currentThread().interrupt()
                  Left(CancelledError("jev", Some(interrupted)))
              }
            }
          }
      }

    attempt(1)
  }
}
