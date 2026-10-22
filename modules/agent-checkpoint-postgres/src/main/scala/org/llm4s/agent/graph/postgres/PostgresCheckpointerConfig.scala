package org.llm4s.agent.graph.postgres

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * How a [[PostgresCheckpointer]] uses its database.
 *
 *  - `schema`: the PostgreSQL schema its tables live in, `llm4s` by default, so the store can share a database with
 *    other tables (and two stores, in two schemas, can share one database without seeing each other). It is created
 *    if it does not exist. A lower-case SQL identifier: a letter or `_`, then letters, digits or `_`, at most 63
 *    characters.
 *  - `callTimeout`: how long one call may hold its connection - every statement it runs, the waits for row locks
 *    another instance holds included - before it fails, changing nothing. It is set on the server as the
 *    transaction's `statement_timeout`, `lock_timeout` and `idle_in_transaction_session_timeout`, and enforced on
 *    the client too: a call still running when it passes has its connection aborted and discarded, so a call whose
 *    network stalls fails at the bound rather than waiting for the socket.
 *  - `connectionTimeout`: how long a call waits for a connection from the store's own pool (see
 *    [[PostgresCheckpointer.open]]); at least 250 milliseconds, HikariCP's minimum. A caller-supplied pool
 *    ([[PostgresCheckpointer.create]]) keeps its own setting, which then takes this one's place in the bound.
 *  - `maxPoolSize`: the size of the store's own pool; ignored for a caller-supplied one.
 *
 * '''The bound.''' Every call returns within `connectionTimeout + callTimeout`, plus the time it takes to roll back or
 * discard a connection: the bound the `Checkpointer` contract asks a store to document (#1919). Keep it well inside
 * `ClaimPolicy.ttl - ClaimPolicy.renewEvery` (the defaults, 5 + 5 seconds, are inside 30 - 10).
 *
 * Durations are rounded up to whole milliseconds and must be positive and at most `Int.MaxValue` milliseconds;
 * `apply` and the `with*` setters throw `IllegalArgumentException` for an invalid value, and
 * [[PostgresCheckpointerConfig.of]] returns a `ValidationError`.
 */
@Experimental
final case class PostgresCheckpointerConfig private (
  schema: String,
  callTimeout: FiniteDuration,
  connectionTimeout: FiniteDuration,
  maxPoolSize: Int
):
  def withSchema(name: String): PostgresCheckpointerConfig =
    PostgresCheckpointerConfig(name, callTimeout, connectionTimeout, maxPoolSize)
  def withCallTimeout(timeout: FiniteDuration): PostgresCheckpointerConfig =
    PostgresCheckpointerConfig(schema, timeout, connectionTimeout, maxPoolSize)
  def withConnectionTimeout(timeout: FiniteDuration): PostgresCheckpointerConfig =
    PostgresCheckpointerConfig(schema, callTimeout, timeout, maxPoolSize)
  def withMaxPoolSize(size: Int): PostgresCheckpointerConfig =
    PostgresCheckpointerConfig(schema, callTimeout, connectionTimeout, size)

  /** The bound every call of a store with its own pool returns within: `connectionTimeout + callTimeout`. */
  def bound: FiniteDuration = connectionTimeout + callTimeout

object PostgresCheckpointerConfig:

  /** The schema a store uses unless told otherwise. */
  val DefaultSchema: String = "llm4s"

  private val Identifier = "[a-z_][a-z0-9_]{0,62}".r

  /** HikariCP refuses a connection timeout below 250 ms. */
  private val MinConnectionTimeout = 250.millis

  private def problems(
    schema: String,
    callTimeout: FiniteDuration,
    connectionTimeout: FiniteDuration,
    maxPoolSize: Int
  ): List[String] =
    List(
      Option.when(!Identifier.matches(schema))(
        s"schema must be a lower-case SQL identifier (a letter or _, then letters, digits or _, at most 63), was '$schema'"
      ),
      Option.when(callTimeout.length <= 0)(s"callTimeout must be positive, was $callTimeout"),
      Option.when(callTimeout > Int.MaxValue.toLong.millis)(
        s"callTimeout must be at most ${Int.MaxValue} milliseconds, was $callTimeout"
      ),
      Option.when(connectionTimeout < MinConnectionTimeout)(
        s"connectionTimeout must be at least $MinConnectionTimeout, was $connectionTimeout"
      ),
      Option.when(connectionTimeout > Int.MaxValue.toLong.millis)(
        s"connectionTimeout must be at most ${Int.MaxValue} milliseconds, was $connectionTimeout"
      ),
      Option.when(maxPoolSize < 1)(s"maxPoolSize must be at least 1, was $maxPoolSize")
    ).flatten

  /** Throws `IllegalArgumentException` for an invalid setting; use [[of]] for untrusted input. */
  def apply(
    schema: String = DefaultSchema,
    callTimeout: FiniteDuration = 5.seconds,
    connectionTimeout: FiniteDuration = 5.seconds,
    maxPoolSize: Int = 10
  ): PostgresCheckpointerConfig =
    val found = problems(schema, callTimeout, connectionTimeout, maxPoolSize)
    require(found.isEmpty, found.mkString("; "))
    new PostgresCheckpointerConfig(schema, callTimeout, connectionTimeout, maxPoolSize)

  def of(
    schema: String = DefaultSchema,
    callTimeout: FiniteDuration = 5.seconds,
    connectionTimeout: FiniteDuration = 5.seconds,
    maxPoolSize: Int = 10
  ): Result[PostgresCheckpointerConfig] =
    problems(schema, callTimeout, connectionTimeout, maxPoolSize) match
      case Nil   => Right(new PostgresCheckpointerConfig(schema, callTimeout, connectionTimeout, maxPoolSize))
      case found => Left(ValidationError("postgres-checkpointer", found))

  /** Schema `llm4s`, 5 second call and connection timeouts, a pool of 10. */
  val default: PostgresCheckpointerConfig = apply()
