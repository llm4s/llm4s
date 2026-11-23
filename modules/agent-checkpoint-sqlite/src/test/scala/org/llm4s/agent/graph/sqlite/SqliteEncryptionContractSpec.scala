package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.encryption.{ EncryptingCheckpointer, EncryptingRunQueue, EncryptionKey, KeyId, StaticKeySource }
import org.llm4s.agent.graph.*
import org.llm4s.agent.service.{ BusyThreadPolicy, QueuedInput, Submission }
import org.llm4s.agent.testkit.{ CheckpointerContract, ManualClock, RunQueueContract }
import org.llm4s.agent.AgentId
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import java.time.{ Clock, Instant }
import scala.concurrent.duration.*

private object SqliteEncryption {
  val keys: StaticKeySource = StaticKeySource(EncryptionKey.generate(KeyId("sqlite-contract")))

  def sqliteOf(store: Checkpointer): SqliteCheckpointer = store match {
    case encrypting: EncryptingCheckpointer => sqliteOf(encrypting.inner)
    case sqlite: SqliteCheckpointer         => sqlite
    case other                              => throw new IllegalStateException(s"not over a SqliteCheckpointer: $other")
  }
}

/**
 * [[EncryptingCheckpointer]] over the SQLite store passes the contract every store must pass (#2449), each case on a
 * file of its own and reopened as a restarted process would: what one process encrypted, the next one reads.
 */
class SqliteEncryptingCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract {
  import SqliteEncryption.*

  private def encrypting(store: SqliteCheckpointer): Checkpointer = EncryptingCheckpointer(store, keys)

  protected def newCheckpointer(clock: Clock): Checkpointer = encrypting(SqliteFiles.opened(SqliteFiles.fresh(), clock))

  override protected def reopen(store: Checkpointer, clock: ManualClock): Checkpointer = {
    val sqlite = sqliteOf(store)
    sqlite.close()
    encrypting(SqliteFiles.opened(sqlite.path, clock))
  }

  override protected def sibling(store: Checkpointer, clock: ManualClock): Checkpointer =
    encrypting(SqliteFiles.opened(sqliteOf(store).path, clock))

  override protected def closeExtra(store: Checkpointer): Unit = sqliteOf(store).close()
}

/** [[EncryptingRunQueue]] over the SQLite store's queue passes the run queue's contract (#2449). */
class SqliteEncryptingRunQueueContractSpec extends AnyFlatSpec with RunQueueContract {
  import SqliteEncryption.*

  private def stores(store: SqliteCheckpointer) =
    RunQueueContract.Stores(EncryptingCheckpointer(store, keys), EncryptingRunQueue(store.runQueue, keys))

  protected def newStores(clock: Clock): RunQueueContract.Stores =
    stores(SqliteFiles.opened(SqliteFiles.fresh(), clock))

  override protected def reopen(current: RunQueueContract.Stores, clock: ManualClock): RunQueueContract.Stores = {
    val sqlite = sqliteOf(current.checkpointer)
    sqlite.close()
    stores(SqliteFiles.opened(sqlite.path, clock))
  }

  override protected def sibling(current: RunQueueContract.Stores, clock: ManualClock): RunQueueContract.Stores =
    stores(SqliteFiles.opened(sqliteOf(current.checkpointer).path, clock))

  override protected def closeExtra(current: RunQueueContract.Stores): Unit = sqliteOf(current.checkpointer).close()
}

/** The bytes of an encrypted SQLite store hold none of what the application committed or queued (#2449). */
class SqliteEncryptionAtRestSpec extends AnyFlatSpec with Matchers with EitherValues {
  import SqliteEncryption.keys

  private val Secret = "the-patient-has-condition-x"
  private val at     = Instant.parse("2026-11-22T12:00:00Z")

  /** Every file SQLite keeps for the database - the main file and its write-ahead log - as text. */
  private def bytesOf(path: Path): String =
    Vector("", "-wal", "-journal")
      .map(suffix => Path.of(s"$path$suffix"))
      .filter(Files.exists(_))
      .map(file => new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1))
      .mkString

  private def fill(checkpointer: Checkpointer, submit: Submission => Any): Unit = {
    val on      = ThreadId("at-rest")
    val payload = VersionedJson(1, ujson.Obj("text" -> Secret))
    val snapshot = GraphSnapshot(
      "g",
      "v1",
      "f",
      0,
      Map("messages" -> payload),
      Vector.empty,
      Vector.empty,
      Vector.empty,
      Vector.empty,
      false
    )
    val checkpoint =
      Checkpoint(Checkpoint.CurrentFormat, "c1", None, on.value, "r1", CheckpointStatus.Running, at, snapshot)
    val write = PendingWrite("c1", "0.0", "n", Vector(EncodedOperation.Update("messages", payload)), Vector.empty)
    val event =
      EventDraft("r1", Some("c1"), None, None, at, RunEvent.Custom("app.note", 1, ujson.Obj("text" -> Secret)))
    val token = checkpointer.claim(on, ClaimRequest(RunId("r1"), None, 30.seconds)).value.token
    checkpointer.commit(on, Commit(token, Some(checkpoint), Vector(write), Vector(event))).value
    submit(
      Submission(
        on,
        AgentId.of("assistant").value,
        QueuedInput.Start(ujson.Obj("query" -> Secret)),
        policy = BusyThreadPolicy.Enqueue
      )
    ): Unit
  }

  "A SQLite store" should "hold the committed payloads in plain without encryption - the check this spec rests on" in {
    val path  = SqliteFiles.fresh()
    val store = SqliteFiles.opened(path, Clock.systemUTC())
    fill(store, s => store.runQueue.submit(s).value)
    store.close()
    bytesOf(path) should include(Secret)
  }

  it should "hold none of them, in its file or its write-ahead log, under EncryptingCheckpointer and EncryptingRunQueue" in {
    val path  = SqliteFiles.fresh()
    val store = SqliteFiles.opened(path, Clock.systemUTC())
    fill(EncryptingCheckpointer(store, keys), s => EncryptingRunQueue(store.runQueue, keys).submit(s).value)
    val live = bytesOf(path)
    store.close()
    val closed = bytesOf(path)

    Seq(live, closed).foreach { bytes =>
      (bytes should not).include(Secret)
      bytes should include("1.sqlite-contract.")
      // the ids the store works by are still there
      bytes should include("at-rest")
    }

    val reopened = SqliteFiles.opened(path, Clock.systemUTC())
    val read     = EncryptingCheckpointer(reopened, keys).latest(ThreadId("at-rest")).value
    read.map(_.checkpoint.snapshot.state("messages").value("text").str) shouldBe Some(Secret)
    reopened.close()
  }
}
