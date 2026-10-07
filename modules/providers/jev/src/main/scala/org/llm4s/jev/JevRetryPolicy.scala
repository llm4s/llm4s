package org.llm4s.jev

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How [[JevClient]] retries a transient failure.
 *
 * The defaults are those of TypeSafe's own client SDKs (https://docs.typesafe.ai/sdk/python/api/retries.md): two
 * retries after the first attempt, a first delay of 0.5 s that doubles up to 5 s with a quarter of each delay
 * randomly taken off, and a budget of 30 s for the whole call. A delay the server asked for (`Retry-After` or
 * `retry-after-ms`) replaces the computed one. What is retried is LLM4S's one retry rule,
 * [[org.llm4s.reliability.RetryPolicy.isTransient]]: a 408, a 429, a 5xx (the API's `529 Overloaded` too), and a
 * connection failure or timeout. A rejected credential (401, 403), an invalid request (400, 422) and a
 * cancellation are never retried.
 *
 * @param maxRetries     retries after the first attempt; `0` sends each request once
 * @param backoffInitial the first delay, doubled for each further retry
 * @param backoffMax     the longest computed delay
 * @param jitter         the fraction of each computed delay that is randomly taken off, from 0 to 1
 * @param budget         the longest a call may take, attempts and delays together: no retry is started whose delay
 *                       would reach what is left of it
 */
final case class JevRetryPolicy private (
  maxRetries: Int,
  backoffInitial: FiniteDuration,
  backoffMax: FiniteDuration,
  jitter: Double,
  budget: FiniteDuration
) {
  def withMaxRetries(maxRetries: Int): JevRetryPolicy                    = copy(maxRetries = maxRetries)
  def withBackoffInitial(backoffInitial: FiniteDuration): JevRetryPolicy = copy(backoffInitial = backoffInitial)
  def withBackoffMax(backoffMax: FiniteDuration): JevRetryPolicy         = copy(backoffMax = backoffMax)
  def withJitter(jitter: Double): JevRetryPolicy                         = copy(jitter = jitter)
  def withBudget(budget: FiniteDuration): JevRetryPolicy                 = copy(budget = budget)

  /** Checks the policy; the first problem is reported, naming the setting. */
  def validate: Result[JevRetryPolicy] =
    if (maxRetries < 0 || maxRetries > JevRetryPolicy.MaxRetriesLimit)
      Left(ConfigurationError(s"llm4s.jev.retry.maxRetries must be between 0 and ${JevRetryPolicy.MaxRetriesLimit}"))
    else if (backoffInitial < Duration.Zero)
      Left(ConfigurationError("llm4s.jev.retry.backoffInitial must not be negative"))
    else if (backoffMax < backoffInitial)
      Left(ConfigurationError("llm4s.jev.retry.backoffMax must not be shorter than backoffInitial"))
    else if (jitter.isNaN || jitter < 0.0 || jitter > 1.0)
      Left(ConfigurationError("llm4s.jev.retry.jitter must be between 0 and 1"))
    else if (budget <= Duration.Zero)
      Left(ConfigurationError("llm4s.jev.retry.budget must be positive"))
    else Right(this)
}

object JevRetryPolicy {

  /** The most retries a policy may ask for: a call that retries without end is a hang. */
  val MaxRetriesLimit: Int = 10

  /** The SDK defaults described on [[JevRetryPolicy]]. */
  val default: JevRetryPolicy = new JevRetryPolicy(2, 500.millis, 5.seconds, 0.25, 30.seconds)

  /** No retries: every request is sent once. */
  val none: JevRetryPolicy = default.withMaxRetries(0)

  /** Creates a policy; the arguments default to the SDK's. Named arguments are the supported way to call it. */
  def apply(
    maxRetries: Int = default.maxRetries,
    backoffInitial: FiniteDuration = default.backoffInitial,
    backoffMax: FiniteDuration = default.backoffMax,
    jitter: Double = default.jitter,
    budget: FiniteDuration = default.budget
  ): JevRetryPolicy = new JevRetryPolicy(maxRetries, backoffInitial, backoffMax, jitter, budget)
}
