package org.llm4s.toolapi

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationInt
import scala.concurrent.duration.DurationLong

/**
 * Controls timeout and retry behaviour for tool calls executed through
 * [[ToolExecutor]].
 *
 * A default configuration applies no timeout and no retry, preserving the
 * original single-attempt execution behaviour. Build one with
 * [[ToolExecutionConfig.apply]] (or start from [[ToolExecutionConfig.default]])
 * and change it with the `with*` setters; the constructor and `copy` are private,
 * so a new field never breaks callers. Pass a configuration to
 * [[ToolExecutor.execute]], [[ToolExecutor.executeAsync]], or
 * [[ToolExecutor.executeAll]] when a tool call should fail after a bounded
 * duration or retry transient failures before returning an error.
 *
 * @param timeout optional maximum duration to wait for each individual tool
 *                call before returning [[ToolCallError.Timeout]]
 * @param retryPolicy optional retry policy for errors considered retryable by
 *                    [[ToolCallError.isRetryable]]
 *
 * @example
 * {{{
 * import org.llm4s.toolapi._
 * import scala.concurrent.duration._
 *
 * for {
 *   registry <- ToolRegistry.of(Seq(myTool))
 *   policy   <- ToolRetryPolicy(maxAttempts = 3, baseDelay = 200.millis)
 *   config = ToolExecutionConfig.default.withTimeout(5.seconds).withRetryPolicy(policy)
 * } yield ToolExecutor.execute(registry, request, config)
 * }}}
 */
@Experimental
final case class ToolExecutionConfig private (
  timeout: Option[FiniteDuration],
  retryPolicy: Option[ToolRetryPolicy]
) {

  /** Sets the maximum duration to wait for each individual tool call. */
  def withTimeout(timeout: FiniteDuration): ToolExecutionConfig = copy(timeout = Some(timeout))

  /** Sets, or with `None` removes, the maximum duration to wait for each individual tool call. */
  def withTimeout(timeout: Option[FiniteDuration]): ToolExecutionConfig = copy(timeout = timeout)

  /** Sets the retry policy for retryable tool errors. */
  def withRetryPolicy(retryPolicy: ToolRetryPolicy): ToolExecutionConfig = copy(retryPolicy = Some(retryPolicy))

  /** Sets, or with `None` removes, the retry policy for retryable tool errors. */
  def withRetryPolicy(retryPolicy: Option[ToolRetryPolicy]): ToolExecutionConfig = copy(retryPolicy = retryPolicy)
}

object ToolExecutionConfig {

  /**
   * Creates a [[ToolExecutionConfig]]; with no arguments, no timeout and no retry.
   *
   * @param timeout     optional maximum duration to wait for each individual tool call
   * @param retryPolicy optional retry policy for retryable tool errors
   */
  def apply(
    timeout: Option[FiniteDuration] = None,
    retryPolicy: Option[ToolRetryPolicy] = None
  ): ToolExecutionConfig =
    new ToolExecutionConfig(timeout, retryPolicy)

  /** No timeout and no retry: a single attempt, as [[ToolRegistry.execute]] behaves. */
  val default: ToolExecutionConfig = apply()
}

/**
 * Retry policy with capped exponential backoff and optional jitter.
 *
 * Build one with [[ToolRetryPolicy.apply]], which checks the values and returns a `Result`; the `with*`
 * setters check them again, so a policy that exists is always valid.
 *
 * @param maxAttempts  Total attempts (first try + retries); at least 1.
 * @param baseDelay    Delay after first failure before first retry; not negative.
 * @param backoffFactor Multiplier for each subsequent delay (e.g. 2.0 => baseDelay, 2*baseDelay, 4*baseDelay); at
 *                     least 1.0.
 * @param jitter       Fraction of each delay, from 0.0 to 1.0, that is randomised so that many callers retrying on the
 *                     same schedule do not hit the backend together. The wait before a retry is drawn uniformly
 *                     between `(1 - jitter) * d` and `d`, where `d` is [[delayBeforeAttempt]]: 0.0 (the default) waits
 *                     exactly `d`, 0.5 is "equal jitter" and 1.0 is "full jitter" (anywhere from zero to `d`). The
 *                     wait is never longer than `d`.
 * @param maxDelay     The longest wait before any retry: the exponential delay stops growing here. Not negative.
 */
@Experimental
final case class ToolRetryPolicy private (
  maxAttempts: Int,
  baseDelay: FiniteDuration,
  backoffFactor: Double,
  jitter: Double,
  maxDelay: FiniteDuration
) {

  /** Sets the total number of attempts (first try + retries); `Left` unless it is at least 1. */
  def withMaxAttempts(maxAttempts: Int): Result[ToolRetryPolicy] =
    ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter, maxDelay)

  /** Sets the delay after the first failure, before the first retry; `Left` if it is negative. */
  def withBaseDelay(baseDelay: FiniteDuration): Result[ToolRetryPolicy] =
    ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter, maxDelay)

  /** Sets the multiplier for each subsequent delay; `Left` unless it is at least 1.0. */
  def withBackoffFactor(backoffFactor: Double): Result[ToolRetryPolicy] =
    ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter, maxDelay)

  /**
   * Sets the fraction of each delay that is randomised, from 0.0 (none) to 1.0 ("full jitter"); see the class
   * comment. `Left` outside that range.
   */
  def withJitter(jitter: Double): Result[ToolRetryPolicy] =
    ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter, maxDelay)

  /** Sets the longest wait before any retry; `Left` if it is negative. */
  def withMaxDelay(maxDelay: FiniteDuration): Result[ToolRetryPolicy] =
    ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter, maxDelay)

  /**
   * Delay before the given retry attempt (1-based: 1 is the first retry, after the
   * initial try). attempt 1 -> baseDelay, attempt 2 -> baseDelay * backoffFactor, etc., never more than
   * [[maxDelay]]. With [[jitter]] set, this is the longest the wait can be.
   */
  def delayBeforeAttempt(attempt: Int): FiniteDuration = {
    // As a Double, so a large attempt number reaches maxDelay instead of overflowing a FiniteDuration.
    val millis = baseDelay.toMillis.toDouble * math.pow(backoffFactor, math.max(0, attempt - 1).toDouble)
    if (millis < maxDelay.toMillis.toDouble) millis.toLong.millis else maxDelay
  }

  /**
   * The wait before the given retry attempt with [[jitter]] applied, for a `sample` drawn uniformly from `[0, 1)`:
   * `sample` 0 gives [[delayBeforeAttempt]] and values towards 1 shorten it by up to `jitter` of it. The result is
   * clamped to `[0, delayBeforeAttempt(attempt)]`.
   */
  private[toolapi] def jitteredDelayBeforeAttempt(attempt: Int, sample: Double): FiniteDuration = {
    val max = delayBeforeAttempt(attempt)
    if (jitter == 0.0) max
    else {
      val millis = math.round(max.toMillis * (1.0 - jitter * sample))
      math.max(0L, math.min(max.toMillis, millis)).millis
    }
  }
}

object ToolRetryPolicy {

  /**
   * Creates a [[ToolRetryPolicy]], or a `ValidationError` naming the first value that is out of range. Named
   * arguments are the supported way to construct one.
   *
   * @param maxAttempts   Total attempts (first try + retries); at least 1.
   * @param baseDelay     Delay after first failure before first retry; not negative.
   * @param backoffFactor Multiplier for each subsequent delay; at least 1.0.
   * @param jitter        Fraction of each delay that is randomised, from 0.0 (the default: exact delays) to 1.0.
   * @param maxDelay      The longest wait before any retry (the default: 30 seconds); not negative.
   */
  def apply(
    maxAttempts: Int,
    baseDelay: FiniteDuration,
    backoffFactor: Double = 2.0,
    jitter: Double = 0.0,
    maxDelay: FiniteDuration = 30.seconds
  ): Result[ToolRetryPolicy] =
    for {
      _ <- check(maxAttempts >= 1, "maxAttempts", s"must be >= 1, but is $maxAttempts")
      _ <- check(baseDelay.length >= 0, "baseDelay", s"must not be negative, but is $baseDelay")
      _ <- check(backoffFactor >= 1.0, "backoffFactor", s"must be >= 1.0, but is $backoffFactor")
      _ <- check(jitter >= 0.0 && jitter <= 1.0, "jitter", s"must be between 0.0 and 1.0, but is $jitter")
      _ <- check(maxDelay.length >= 0, "maxDelay", s"must not be negative, but is $maxDelay")
    } yield new ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter, maxDelay)

  private def check(ok: Boolean, field: String, reason: String): Result[Unit] =
    if (ok) Right(()) else Left(ValidationError(field, reason))
}
