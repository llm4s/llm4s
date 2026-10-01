package org.llm4s.reliability

/**
 * Thread-safe token bucket behind [[RateLimitConfig]]: holds up to `burstCapacity` tokens and
 * refills at `requestsPerMinute`. Each [[tryAcquire]] takes one token, if there is one.
 *
 * Refill credits whole tokens and keeps the remainder: the clock advances only by the time the
 * credited tokens represent, so polling more often than the refill interval loses no rate.
 *
 * @param timeSource nanosecond clock; injectable for tests
 */
final private[reliability] class TokenBucket(
  requestsPerMinute: Int,
  burstCapacity: Int,
  timeSource: () => Long = () => System.nanoTime()
) {
  private val capacity: Long = burstCapacity.toLong

  /** Nanoseconds per token, or `None` when the bucket never refills. */
  private val nanosPerToken: Option[Long] =
    Option.when(requestsPerMinute > 0)(60_000_000_000L / requestsPerMinute)

  private var tokens: Long     = capacity
  private var lastRefill: Long = timeSource()

  def tryAcquire(): Boolean = synchronized {
    refill()
    if (tokens > 0) {
      tokens -= 1
      true
    } else false
  }

  /**
   * How long until a token is available: `Some(0)` when one is now, `None` when the bucket is
   * empty and never refills.
   */
  def nanosUntilNextToken: Option[Long] = synchronized {
    refill()
    if (tokens > 0) Some(0L)
    else nanosPerToken.map(per => math.max(0L, per - (timeSource() - lastRefill)))
  }

  private def refill(): Unit = nanosPerToken.foreach { per =>
    val now = timeSource()
    if (tokens >= capacity) lastRefill = now // a full bucket banks no time
    else {
      val credited = (now - lastRefill) / per
      if (credited > 0) {
        tokens = math.min(capacity, tokens + credited)
        lastRefill = if (tokens >= capacity) now else lastRefill + credited * per
      }
    }
  }
}
