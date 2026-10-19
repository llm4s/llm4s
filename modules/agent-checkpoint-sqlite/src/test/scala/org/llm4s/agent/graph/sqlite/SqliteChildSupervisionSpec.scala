package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.{ Checkpointer, ChildSupervisionBehaviours }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/**
 * Child-run supervision (design 4.42) on the SQLite store: each storage is a file of its own, and each restart opens it
 * again on a new connection, as a restarted process would.
 */
class SqliteChildSupervisionSpec extends AnyFlatSpec with ChildSupervisionBehaviours {
  protected def storage(): () => Checkpointer = {
    val db = SqliteFiles.fresh()
    () => SqliteFiles.opened(db, Clock.systemUTC())
  }
}
