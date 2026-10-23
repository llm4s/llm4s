package org.llm4s.toolapi

import org.llm4s.annotation.Stable

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.DurationLong

/**
 * Controls timeout and retry behaviour for tool calls executed through a
 * [[ToolRegistry]].
 *
 * A default configuration applies no timeout and no retry, preserving the
 * original single-attempt execution behaviour. Build one with
 * [[ToolExecutionConfig.apply]] (or start from [[ToolExecutionConfig.default]])
 * and change it with the `with*` setters; the constructor and `copy` are private,
 * so a new field never breaks callers. Pass a configuration to
 * [[ToolRegistry.execute]], [[ToolRegistry.executeAsync]], or
 * [[ToolRegistry.executeAll]] when a tool call should fail after a bounded
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
 * import scala.concurrent.ExecutionContext.Implicits.global
 * import scala.concurrent.duration._
 *
 * val config = ToolExecutionConfig.default
 *   .withTimeout(5.seconds)
 *   .withRetryPolicy(ToolRetryPolicy(maxAttempts = 3, baseDelay = 200.millis))
 *
 * val registry = new ToolRegistry(Seq(myTool))
 * registry.execute(request, config)
 * registry.executeAll(requests, ToolExecutionStrategy.ParallelWithLimit(2), config)
 * }}}
 */
@Stable
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

  /** No timeout and no retry: a single attempt, as [[ToolRegistry]] behaves without a configuration. */
  val default: ToolExecutionConfig = apply()
}

/**
 * Simple retry policy with exponential backoff and optional jitter.
 *
 * Build one with [[ToolRetryPolicy.apply]] and change it with the `with*` setters.
 *
 * @param maxAttempts  Total attempts (first try + retries); must be >= 1.
 * @param baseDelay    Delay after first failure before first retry.
 * @param backoffFactor Multiplier for each subsequent delay (e.g. 2.0 => baseDelay, 2*baseDelay, 4*baseDelay).
 * @param jitter       Fraction of each delay, from 0.0 to 1.0, that is randomised so that many callers retrying on the
 *                     same schedule do not hit the backend together. The wait before a retry is drawn uniformly
 *                     between `(1 - jitter) * d` and `d`, where `d` is [[delayBeforeAttempt]]: 0.0 (the default) waits
 *                     exactly `d`, 0.5 is "equal jitter" and 1.0 is "full jitter" (anywhere from zero to `d`). The
 *                     wait is never longer than `d`.
 */
@Stable
final case class ToolRetryPolicy private (
  maxAttempts: Int,
  baseDelay: FiniteDuration,
  backoffFactor: Double,
  jitter: Double
) {
  require(maxAttempts >= 1, "maxAttempts must be >= 1")
  require(backoffFactor >= 1.0, "backoffFactor must be >= 1.0")
  require(jitter >= 0.0 && jitter <= 1.0, "jitter must be between 0.0 and 1.0")

  /** Sets the total number of attempts (first try + retries); must be >= 1. */
  def withMaxAttempts(maxAttempts: Int): ToolRetryPolicy = copy(maxAttempts = maxAttempts)

  /** Sets the delay after the first failure, before the first retry. */
  def withBaseDelay(baseDelay: FiniteDuration): ToolRetryPolicy = copy(baseDelay = baseDelay)

  /** Sets the multiplier for each subsequent delay; must be >= 1.0. */
  def withBackoffFactor(backoffFactor: Double): ToolRetryPolicy = copy(backoffFactor = backoffFactor)

  /**
   * Sets the fraction of each delay that is randomised, from 0.0 (none) to 1.0 ("full jitter"); see the class comment.
   */
  def withJitter(jitter: Double): ToolRetryPolicy = copy(jitter = jitter)

  /**
   * Delay before the given retry attempt (1-based: 1 is the first retry, after the
   * initial try). attempt 1 -> baseDelay, attempt 2 -> baseDelay * backoffFactor, etc.
   * With [[jitter]] set, this is the longest the wait can be.
   */
  def delayBeforeAttempt(attempt: Int): FiniteDuration =
    (baseDelay.toMillis * math.pow(backoffFactor, (attempt - 1).toDouble)).toLong.millis

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
   * Creates a [[ToolRetryPolicy]]. Named arguments are the supported way to construct one.
   *
   * @param maxAttempts   Total attempts (first try + retries); must be >= 1.
   * @param baseDelay     Delay after first failure before first retry.
   * @param backoffFactor Multiplier for each subsequent delay; must be >= 1.0.
   * @param jitter        Fraction of each delay that is randomised, from 0.0 (the default: exact delays) to 1.0.
   */
  def apply(
    maxAttempts: Int,
    baseDelay: FiniteDuration,
    backoffFactor: Double = 2.0,
    jitter: Double = 0.0
  ): ToolRetryPolicy =
    new ToolRetryPolicy(maxAttempts, baseDelay, backoffFactor, jitter)
}
