package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.*
import org.llm4s.agent.testkit.{ CheckpointerContract, ManualClock }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec

import java.nio.file.{ Files, Path }
import java.time.Clock
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration.FiniteDuration

/** Fresh database files under one temporary directory, and opening a store that must open. */
object SqliteFiles:
  lazy val directory: Path = Files.createTempDirectory("llm4s-sqlite-checkpointer")

  def fresh(): Path = directory.resolve(s"${UUID.randomUUID()}.db")

  def opened(path: Path, clock: Clock): SqliteCheckpointer =
    SqliteCheckpointer.open(path, clock).fold(e => throw new IllegalStateException(e.message), identity)

/**
 * The SQLite store passes the contract every store must pass, each case on a file of its own, and with `reopen`
 * closing the connection and opening the same file again, as a restarted process would.
 */
class SqliteCheckpointerContractSpec extends AnyFlatSpec with CheckpointerContract {

  protected def newCheckpointer(clock: Clock): Checkpointer = SqliteFiles.opened(SqliteFiles.fresh(), clock)

  override protected def reopen(store: Checkpointer, clock: ManualClock): Checkpointer =
    store match {
      case sqlite: SqliteCheckpointer =>
        sqlite.close()
        SqliteFiles.opened(sqlite.path, clock)
      case other => fail(s"not a SqliteCheckpointer: $other")
    }
}

/**
 * Two stores - two connections - over one file, each call going to the next of them in turn: the contract holds
 * when what one connection wrote is read, checked and fenced through the other, and the cases that contend from
 * many threads or several runtimes then contend across connections, through SQLite's own locking.
 */
class SqliteTwoConnectionsContractSpec extends AnyFlatSpec with CheckpointerContract {

  protected def newCheckpointer(clock: Clock): Checkpointer = {
    val path = SqliteFiles.fresh()
    Alternating(path, clock, Vector(SqliteFiles.opened(path, clock), SqliteFiles.opened(path, clock)))
  }

  override protected def reopen(store: Checkpointer, clock: ManualClock): Checkpointer =
    store match {
      case alternating: Alternating =>
        alternating.stores.foreach(_.close())
        Alternating(
          alternating.path,
          clock,
          Vector(SqliteFiles.opened(alternating.path, clock), SqliteFiles.opened(alternating.path, clock))
        )
      case other => fail(s"not an alternating store: $other")
    }

  final private case class Alternating(path: Path, clock: Clock, stores: Vector[SqliteCheckpointer])
      extends Checkpointer {
    private val turn               = new AtomicInteger()
    private def next: Checkpointer = stores(Math.floorMod(turn.getAndIncrement(), stores.size))
    def claim(threadId: ThreadId, request: ClaimRequest): Result[RunClaim] = next.claim(threadId, request)
    def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration): Result[RunClaim] =
      next.renew(threadId, token, ttl)
    def release(threadId: ThreadId, token: FencingToken): Result[Unit]          = next.release(threadId, token)
    def commit(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] = next.commit(threadId, commit)
    def latest(threadId: ThreadId): Result[Option[StoredCheckpoint]]            = next.latest(threadId)
    def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
      next.eventsAfter(threadId, afterSeq, limit)
    def compactEvents(threadId: ThreadId, beforeSeq: Long): Result[Unit] = next.compactEvents(threadId, beforeSeq)
    def deleteThread(threadId: ThreadId): Result[Unit]                   = next.deleteThread(threadId)
  }
}
