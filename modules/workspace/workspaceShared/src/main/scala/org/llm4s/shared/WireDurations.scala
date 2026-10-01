package org.llm4s.shared

import upickle.default.{ ReadWriter, readwriter }

import scala.concurrent.duration.*

/**
 * How the workspace protocol carries a duration: a whole number of seconds, rounded up, so a
 * runner image built before durations were typed reads the same JSON it always did.
 */
private[llm4s] object WireDurations {

  implicit val wholeSecondsRW: ReadWriter[FiniteDuration] =
    readwriter[Long].bimap[FiniteDuration](toWholeSeconds, _.seconds)

  /** `d` in whole seconds, rounded up: a sub-second timeout is still a timeout. */
  def toWholeSeconds(d: FiniteDuration): Long = {
    val seconds = d.toSeconds
    if (d > seconds.seconds) seconds + 1 else seconds
  }
}
