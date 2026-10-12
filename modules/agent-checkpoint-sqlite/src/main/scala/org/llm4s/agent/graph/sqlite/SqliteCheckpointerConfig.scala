package org.llm4s.agent.graph.sqlite

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How a [[SqliteCheckpointer]] uses its database connection.
 *
 * `busyTimeout` is how long a write waits for the database's write lock while another connection - another
 * store over the same file, in this process or another - holds it, before the call fails. SQLite allows one
 * writer at a time per file, and every write here is short (one transaction per call), so a wait is brief; a
 * call that still times out returns a `ProcessingError` and changes nothing. It is rounded up to whole
 * milliseconds, and must be positive and at most `Int.MaxValue` milliseconds: `apply` and the `with*` setters
 * throw `IllegalArgumentException` otherwise, and [[SqliteCheckpointerConfig.of]] returns a `ValidationError`.
 *
 * The journal mode and sync level are not configurable: the store always runs in WAL mode with
 * `synchronous = FULL`, so readers never block the writer and a commit that returned survives a power loss.
 */
final case class SqliteCheckpointerConfig private (busyTimeout: FiniteDuration):
  def withBusyTimeout(timeout: FiniteDuration): SqliteCheckpointerConfig = SqliteCheckpointerConfig(timeout)

object SqliteCheckpointerConfig:

  private def problems(busyTimeout: FiniteDuration): List[String] =
    List(
      Option.when(busyTimeout.length <= 0)(s"busyTimeout must be positive, was $busyTimeout"),
      Option.when(busyTimeout > Int.MaxValue.toLong.millis)(
        s"busyTimeout must be at most ${Int.MaxValue} milliseconds, was $busyTimeout"
      )
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid `busyTimeout`; use [[of]] for untrusted input. */
  def apply(busyTimeout: FiniteDuration = 5.seconds): SqliteCheckpointerConfig =
    val found = problems(busyTimeout)
    require(found.isEmpty, found.mkString("; "))
    new SqliteCheckpointerConfig(busyTimeout)

  def of(busyTimeout: FiniteDuration = 5.seconds): Result[SqliteCheckpointerConfig] =
    problems(busyTimeout) match
      case Nil   => Right(new SqliteCheckpointerConfig(busyTimeout))
      case found => Left(ValidationError("sqlite-checkpointer", found))

  /** A 5 second busy timeout. */
  val default: SqliteCheckpointerConfig = apply()
