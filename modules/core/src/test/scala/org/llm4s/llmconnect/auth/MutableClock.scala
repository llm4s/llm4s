package org.llm4s.llmconnect.auth

import java.time.{ Clock, Instant, ZoneId, ZoneOffset }
import scala.concurrent.duration.FiniteDuration

/** A clock a test moves by hand. */
final class MutableClock(start: Instant) extends Clock:
  @volatile private var now: Instant         = start
  def advance(by: FiniteDuration): Unit      = now = now.plusMillis(by.toMillis)
  override def instant(): Instant            = now
  override def getZone: ZoneId               = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this
