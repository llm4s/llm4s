package org.llm4s.reliability

import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe token bucket behind [[RateLimitConfig]]: holds up to `burstCapacity` tokens and
 * refills at `requestsPerMinute`. Each [[tryAcquire]] takes one token, if there is one.
 *
 * @param timeSource nanosecond clock; injectable for tests
 */
final private[reliability] class TokenBucket(
  requestsPerMinute: Int,
  burstCapacity: Int,
  timeSource: () => Long = () => System.nanoTime()
) {
  private val capacity            = burstCapacity.toLong
  private val refillRatePerNano   = requestsPerMinute.toDouble / 60_000_000_000L
  private val tokens              = new AtomicLong(capacity)
  private val lastRefillTimestamp = new AtomicLong(timeSource())

  @scala.annotation.tailrec
  def tryAcquire(): Boolean = {
    refill()
    val current = tokens.get()
    if (current > 0) {
      if (tokens.compareAndSet(current, current - 1)) true
      else tryAcquire()
    } else false
  }

  private def refill(): Unit = {
    val now  = timeSource()
    val last = lastRefillTimestamp.get()
    if (now > last) {
      val tokensToAdd = ((now - last) * refillRatePerNano).toLong
      // Only advance the timestamp when a whole token is added, so fractional refill accumulates
      if (tokensToAdd > 0 && lastRefillTimestamp.compareAndSet(last, now)) {
        tokens.updateAndGet(t => Math.min(capacity, t + tokensToAdd))
      }
    }
  }
}
