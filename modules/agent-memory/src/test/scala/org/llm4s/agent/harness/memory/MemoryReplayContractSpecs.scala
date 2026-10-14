package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.{ ReplaySupport, RunContext }
import org.llm4s.agent.memory.*
import org.llm4s.agent.testkit.ReplayContract
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec

/**
 * C4's `ReplayContract` over the semantic-memory write (design §4.31): each slot records one memory through
 * [[MemoryEffects.write]], the path recording and consolidation take. A second application shows as a second memory
 * (ids are fresh each attempt); another thread's change is a consolidation that removes the slot's memory.
 */
final class MemoryReplaySubject(val name: String, store: () => MemoryStore, val support: ReplaySupport)
    extends ReplayContract.Subject:
  private lazy val cell = new TenantMemory(store())

  private def content(slot: Int) = s"slot $slot"

  def perform(context: RunContext, slot: Int): Result[ujson.Value] =
    val key = context.effectKey("memory.record", s"slot-$slot")
    MemoryEffects
      .write(context, cell, key, MemoryBounds()) {
        Right(Seq(Memory(MemoryId.generate(), content(slot), MemoryType.Conversation)) -> Nil)
      }
      .map(out => ujson.Num(out.added.size.toDouble))

  def observe(slot: Int): Result[String] =
    cell.current.recall(MemoryFilter.All, Int.MaxValue).map(_.count(_.content == content(slot)).toString)

  def initial(slot: Int): String = "0"
  def once(slot: Int): String    = "1"

  /** Another thread consolidates the slot's memory away, under its own scope. */
  def interfere(slot: Int): Unit =
    val ids =
      cell.current.recall(MemoryFilter.All, Int.MaxValue).toOption.get.filter(_.content == content(slot)).map(_.id)
    if cell.transactional then
      val other = AppliedWriteKey("another-thread", java.util.UUID.randomUUID().toString)
      cell
        .write(s =>
          s.applyAtomically(other, 0L, Nil, ids, MemoryBounds())
            .flatMap((n, _) => n.forgetApplied(other, 0L).map(_ -> ()))
        )
        .toOption
        .get
    else cell.writePlain(Nil, ids, MemoryBounds()).toOption.get: Unit

  override def appliedKeys: Int = cell.appliedCount.toOption.get

class InMemoryMemoryReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject =
    MemoryReplaySubject("an InMemoryStore", () => InMemoryStore.empty, ReplaySupport.Transactional)

class SqliteMemoryReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject =
    MemoryReplaySubject(
      "a SQLiteMemoryStore",
      () => SQLiteMemoryStore.inMemory().fold(e => sys.error(e.message), identity),
      ReplaySupport.Transactional
    )

class UnverifiableMemoryReplayContractSpec extends AnyFlatSpec with ReplayContract:
  protected def newSubject(): ReplayContract.Subject =
    MemoryReplaySubject(
      "a store that is not transactional",
      () => new MemoryTestSupport.PlainStore(InMemoryStore.empty),
      ReplaySupport.Unverifiable
    )
