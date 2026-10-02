package org.llm4s.util

import scala.concurrent.duration.{ DurationLong, FiniteDuration }

/**
 * Converts a [[scala.concurrent.duration.FiniteDuration]] to the whole units an API takes,
 * rounding up.
 *
 * `toMillis` and `toSeconds` truncate, so a positive duration shorter than one unit becomes `0` -
 * which `HttpURLConnection` reads as "no timeout", `HttpServer.stop` as "close now" and
 * `Process.waitFor` as "do not wait". Rounding up keeps every positive duration positive.
 * Zero and negative durations are passed through unchanged.
 */
private[llm4s] object DurationRounding {

  /** `d` in whole milliseconds, rounded up. */
  def ceilMillis(d: FiniteDuration): Long = {
    val millis = d.toMillis
    if (d > millis.millis) millis + 1 else millis
  }

  /** [[ceilMillis]], capped at `Int.MaxValue`, for APIs such as `URLConnection.setConnectTimeout`. */
  def ceilMillisInt(d: FiniteDuration): Int = math.min(ceilMillis(d), Int.MaxValue.toLong).toInt

  /** `d` in whole seconds, rounded up. */
  def ceilSeconds(d: FiniteDuration): Long = {
    val seconds = d.toSeconds
    if (d > seconds.seconds) seconds + 1 else seconds
  }

  /** [[ceilSeconds]], capped at `Int.MaxValue`. */
  def ceilSecondsInt(d: FiniteDuration): Int = math.min(ceilSeconds(d), Int.MaxValue.toLong).toInt
}
