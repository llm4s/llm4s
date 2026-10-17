package org.llm4s.agent.graph.sqlite

import org.llm4s.agent.graph.Checkpointer
import org.llm4s.agent.testkit.{ ReplayContract, ReplaySubjects }
import org.scalatest.flatspec.AnyFlatSpec

import java.time.Clock

/** [[ReplayContract]] for each support class over the SQLite store: its effect records are the durable journal. */
trait SqliteReplay extends ReplayContract:
  override protected def newCheckpointer(clock: Clock): Checkpointer = SqliteFiles.opened(SqliteFiles.fresh(), clock)

class SqliteTransactionalReplayContractSpec extends AnyFlatSpec with SqliteReplay:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.TransactionalCounters()

class SqliteVerifiableReplayContractSpec extends AnyFlatSpec with SqliteReplay:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.VerifiableText()

class SqliteUnverifiableReplayContractSpec extends AnyFlatSpec with SqliteReplay:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.UnverifiableLog()

class SqliteIdempotentReplayContractSpec extends AnyFlatSpec with SqliteReplay:
  protected def newSubject(): ReplayContract.Subject = ReplaySubjects.IdempotentFlag()
