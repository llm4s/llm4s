package org.llm4s.agent.testkit

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration.FiniteDuration

/**
 * A clock that moves only when told to, for driving claim expiry in a test: hand it to the store
 * under test, and [[advance]] it past a claim's `ttl` to let another run take the thread over.
 * Safe to read and move from any thread.
 */
final class ManualClock(start: Instant, zone: ZoneId = ZoneOffset.UTC) extends Clock:
  private val current = AtomicReference(start)

  def instant(): Instant = current.get

  def getZone: ZoneId = zone

  override def withZone(other: ZoneId): Clock = ManualClockView(this, other)

  /** Moves the clock forward by `by`. */
  def advance(by: FiniteDuration): Unit = current.updateAndGet(_.plusNanos(by.toNanos)): Unit

  /** Sets the clock to `to`, which may be earlier than now. */
  def set(to: Instant): Unit = current.set(to)

/** [[ManualClock]] in another zone: the same instant, moved by the same calls. */
final private class ManualClockView(underlying: ManualClock, zone: ZoneId) extends Clock:
  def instant(): Instant                      = underlying.instant()
  def getZone: ZoneId                         = zone
  override def withZone(other: ZoneId): Clock = ManualClockView(underlying, other)
