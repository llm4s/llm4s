package org.llm4s.toolapi

import scala.concurrent.duration.FiniteDuration

/**
 * Controls timeout and retry behaviour for tool calls executed through a
 * [[ToolRegistry]].
 *
 * A default configuration applies no timeout and no retry, preserving the
 * original single-attempt execution behaviour. Pass a configuration to
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
 * val config = ToolExecutionConfig(
 *   timeout = Some(5.seconds),
 *   retryPolicy = Some(ToolRetryPolicy(maxAttempts = 3, baseDelay = 200.millis))
 * )
 *
 * val registry = new ToolRegistry(Seq(myTool))
 * registry.execute(request, config)
 * registry.executeAll(requests, ToolExecutionStrategy.ParallelWithLimit(2), config)
 * }}}
 */
case class ToolExecutionConfig(
  timeout: Option[FiniteDuration] = None,
  retryPolicy: Option[ToolRetryPolicy] = None
)

/**
 * Simple retry policy with exponential backoff.
 *
 * @param maxAttempts  Total attempts (first try + retries); must be >= 1.
 * @param baseDelay    Delay after first failure before first retry.
 * @param backoffFactor Multiplier for each subsequent delay (e.g. 2.0 => baseDelay, 2*baseDelay, 4*baseDelay).
 */
case class ToolRetryPolicy(
  maxAttempts: Int,
  baseDelay: FiniteDuration,
  backoffFactor: Double = 2.0
) {
  require(maxAttempts >= 1, "maxAttempts must be >= 1")
  require(backoffFactor >= 1.0, "backoffFactor must be >= 1.0")
}
