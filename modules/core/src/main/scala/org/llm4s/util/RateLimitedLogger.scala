package org.llm4s.util

import org.slf4j.Logger

import java.util.concurrent.atomic.{ AtomicInteger, AtomicLong }

/**
 * Thread-safe rate-limited logger to prevent log spam.
 * Logs at most once per time window OR once per count threshold, whichever comes first.
 * Aggregates skipped count between log messages.
 *
 * Thread-safety via AtomicLong and AtomicInteger ensures visibility and atomic operations.
 * Multiple threads may pass shouldLog check, but compareAndSet ensures only one logs.
 * Minor event count drift is acceptable for best-effort logging.
 *
 * SLF4J dependency provided transitively via logback-classic.
 *
 * @param logger SLF4J logger instance
 * @param throttleSeconds Minimum seconds between log messages
 * @param throttleCount Maximum events before forcing a log
 */
final class RateLimitedLogger(
  logger: Logger,
  throttleSeconds: Long = 60,
  throttleCount: Int = 100
) {

  private val lastLogTime        = new AtomicLong(0L)
  private val eventsSinceLastLog = new AtomicInteger(0)

  /**
   * Log a warning message with rate limiting (thread-safe).
   *
   * @param message Warning message (call-by-name, only evaluated if logged)
   * @return true if message was logged, false if throttled
   */
  def warn(message: => String): Boolean = {
    val events  = eventsSinceLastLog.incrementAndGet()
    val now     = System.currentTimeMillis() / 1000
    val lastLog = lastLogTime.get()

    val shouldLog = (now - lastLog >= throttleSeconds) || (events >= throttleCount)

    if (shouldLog && lastLogTime.compareAndSet(lastLog, now)) {
      // CAS succeeded - we won the race to log
      val count        = eventsSinceLastLog.getAndSet(0)
      val aggregateMsg = if (count > 1) s" ($count events since last log)" else ""
      logger.warn(message + aggregateMsg)
      true
    } else {
      false
    }
  }

  /**
   * Reset the rate limiter state.
   * Useful for testing or explicit state clearing.
   */
  def reset(): Unit = {
    lastLogTime.set(0L)
    eventsSinceLastLog.set(0)
  }
}

object RateLimitedLogger {

  /** Create rate limiter with default thresholds (60s, 100 events) */
  def apply(logger: Logger): RateLimitedLogger =
    new RateLimitedLogger(logger)

  /** Create rate limiter with custom thresholds */
  def apply(logger: Logger, throttleSeconds: Long, throttleCount: Int): RateLimitedLogger =
    new RateLimitedLogger(logger, throttleSeconds, throttleCount)
}
