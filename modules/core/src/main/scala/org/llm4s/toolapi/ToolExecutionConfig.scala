package org.llm4s.toolapi

import scala.concurrent.duration.FiniteDuration

/**
 * Configuration for tool execution: optional per-tool timeout and retry policy.
 *
 * Default is no timeout and no retry (backward compatible).
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
