package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.service.EntryId
import org.llm4s.agent.testkit.{ ManualClock, RunQueueContract }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/**
 * The SQLite store's queue passes the contract every run queue must pass, each case on a file of its own, `reopen`
 * closing the connection and opening the file again and `sibling` opening another connection to it, as another
 * process would.
 */
class SqliteRunQueueContractSpec extends AnyFlatSpec with RunQueueContract {

  private def stores(store: SqliteCheckpointer) = RunQueueContract.Stores(store, store.runQueue)

  protected def newStores(clock: Clock): RunQueueContract.Stores =
    stores(SqliteFiles.opened(SqliteFiles.fresh(), clock))

  override protected def reopen(current: RunQueueContract.Stores, clock: ManualClock): RunQueueContract.Stores =
    current.checkpointer match {
      case sqlite: SqliteCheckpointer =>
        sqlite.close()
        stores(SqliteFiles.opened(sqlite.path, clock))
      case other => fail(s"not a SqliteCheckpointer: $other")
    }

  override protected def sibling(current: RunQueueContract.Stores, clock: ManualClock): RunQueueContract.Stores =
    current.checkpointer match {
      case sqlite: SqliteCheckpointer => stores(SqliteFiles.opened(sqlite.path, clock))
      case other                      => fail(s"not a SqliteCheckpointer: $other")
    }

  override protected def spoilInput(current: RunQueueContract.Stores, entryId: EntryId): Boolean =
    current.checkpointer match {
      case sqlite: SqliteCheckpointer =>
        sqlite
          .writing("spoil") { c =>
            SqliteCheckpointer.update(
              c,
              "UPDATE llm4s_checkpoint_queue_entries SET input_json = ? WHERE entry_id = ?",
              """{"kind":"from-a-newer-build"}""",
              entryId.value
            )
            Right(())
          }
          .isRight
      case _ => false
    }
}
