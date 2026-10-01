package org.llm4s.util

import scala.concurrent.duration.FiniteDuration

/**
 * Compact, human-readable rendering of a [[scala.concurrent.duration.FiniteDuration]] for
 * error messages and diagnostic context: `250ms`, `1s`, `1.5s`, `90s`.
 *
 * Sub-second durations render in milliseconds, anything longer in seconds (fractional when
 * not whole), so a message never leaves the reader guessing the unit.
 */
private[llm4s] object DurationText {

  def apply(duration: FiniteDuration): String = {
    val millis = duration.toMillis
    if (math.abs(millis) < 1000L) s"${millis}ms"
    else if (millis % 1000L == 0L) s"${millis / 1000L}s"
    else s"${java.math.BigDecimal.valueOf(millis, 3).stripTrailingZeros.toPlainString}s"
  }
}
