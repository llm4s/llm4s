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

  /**
   * A duration the protocol carries as whole milliseconds, such as a command's `durationMs`.
   * Not implicit at the top level of a file that also uses [[wholeSecondsRW]]: import it in the
   * companion that needs it.
   */
  val millisRW: ReadWriter[FiniteDuration] =
    readwriter[Long].bimap[FiniteDuration](_.toMillis, _.millis)

  /** `d` in whole milliseconds, rounded up, so a positive timeout never becomes `waitFor(0)`. */
  def toWholeMillis(d: FiniteDuration): Long = {
    val millis = d.toMillis
    if (d > millis.millis) millis + 1 else millis
  }
}
