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
 * policy's exponential backoff with jitter. The whole call is bounded by the policy's budget: each attempt is handed
 * what is left of it, so it can cap its own timeout there, and when the wait before a retry would reach what is left,
 * the last error is returned instead. The last error is returned as it is, so its type says why the call failed.
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

  /**
   * Runs `operation`, retrying a transient failure as the policy allows.
   *
   * @param operation one attempt, given the time left in the budget when it starts (always positive). An attempt
   *                  that may block, such as an HTTP call, must not wait longer than that, or the call overruns the
   *                  budget.
   */
  def run[A](operation: FiniteDuration => Result[A]): Result[A] = {
    val start = nanoTime()

    def remaining(): FiniteDuration = policy.budget - FiniteDuration(nanoTime() - start, NANOSECONDS)

    @tailrec def attempt(number: Int, left: FiniteDuration): Result[A] =
      CancelledError.attempt("jev")(operation(left)) match {
        case success @ Right(_) => success
        case failure @ Left(error) =>
          if (number > policy.maxRetries || !RetryPolicy.isTransient(error)) failure
          else {
            val delay = serverHint(error).getOrElse(backoff(number))
            // compared with what is left rather than added to what has passed: a server's delay can be close to the
            // largest FiniteDuration, and the sum would overflow
            if (delay >= remaining()) {
              logger.debug("Jev call out of retry budget after {} attempt(s)", number)
              failure
            } else {
              logger.debug("Jev attempt {} failed with {}; retrying in {}", number, error.getClass.getSimpleName, delay)
              CancelledError.catchInterrupt(sleep(delay)) match {
                case Right(()) =>
                  val left = remaining()
                  if (left > Duration.Zero) attempt(number + 1, left)
                  else {
                    logger.debug("Jev call out of retry budget after {} attempt(s)", number)
                    failure
                  }
                case Left(interrupted) =>
                  Thread.currentThread().interrupt()
                  Left(CancelledError("jev", Some(interrupted)))
              }
            }
          }
      }

    attempt(1, policy.budget)
  }
}
