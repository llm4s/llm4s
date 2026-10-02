package org.llm4s.util

import upickle.default.{ ReadWriter, readwriter }

import scala.concurrent.duration.{ DurationLong, FiniteDuration }

/**
 * JSON for a [[scala.concurrent.duration.FiniteDuration]] that a wire format already carries as a
 * number of milliseconds, so typing the Scala field does not change the JSON.
 *
 * Import `millisRW` where a `macroRW` derives a codec for a type with such a field, and pin the
 * field's old key with `@upickle.implicits.key` if its name changed.
 */
private[llm4s] object DurationJson {

  /** A whole number of milliseconds; sub-millisecond precision is dropped on write. */
  implicit val millisRW: ReadWriter[FiniteDuration] =
    readwriter[Long].bimap[FiniteDuration](_.toMillis, _.millis)
}
