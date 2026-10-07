package org.llm4s.agent.memory

import java.sql.Connection
import scala.util.{ Try, Using }

/**
 * Write transactions for the SQLite-backed stores, on a connection left in JDBC autocommit mode.
 *
 * The transaction is plain SQL - `BEGIN IMMEDIATE`, then `COMMIT`, or `ROLLBACK` if anything (fatal errors
 * included) escapes before the commit - rather than `setAutoCommit(false)` and `commit()`. sqlite-jdbc implements
 * those by clearing its autocommit flag before it runs `BEGIN`, and by running the next `BEGIN` straight after every
 * `COMMIT`: a `BEGIN` that times out on a busy database then leaves the connection believing a transaction is open
 * when none is, so every later write runs unguarded and its `commit()` fails; and a `COMMIT` that succeeded can still
 * be reported as a failure. Here a failed `BEGIN` leaves the connection exactly as it was, and success is reported
 * exactly when `COMMIT` succeeded.
 *
 * `IMMEDIATE` takes the write lock up front, waiting up to the connection's busy timeout for another writer on the
 * same file. A `DEFERRED` transaction that read first could not wait: SQLite fails its upgrade at once rather than
 * risk a deadlock.
 */
private[memory] object SqliteTransaction {

  /** Run `body` in one `BEGIN IMMEDIATE` ... `COMMIT`; roll back if it does not complete. */
  def immediate[A](connection: Connection)(body: => A): A = {
    execute(connection, "BEGIN IMMEDIATE")
    Using.resource(new RollbackUnlessCommitted(connection)) { guard =>
      val result = body
      execute(connection, "COMMIT")
      guard.committed = true
      result
    }
  }

  private def execute(connection: Connection, sql: String): Unit =
    Using.resource(connection.createStatement())(_.execute(sql): Unit)

  final private class RollbackUnlessCommitted(connection: Connection) extends AutoCloseable {
    var committed = false
    override def close(): Unit =
      if (!committed) Try(execute(connection, "ROLLBACK")): Unit
  }
}
