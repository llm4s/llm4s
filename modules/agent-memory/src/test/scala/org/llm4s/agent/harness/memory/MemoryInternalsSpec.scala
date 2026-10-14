package org.llm4s.agent.harness.memory

import org.llm4s.agent.graph.FencingToken
import org.llm4s.agent.memory.*
import org.llm4s.error.ValidationError
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/** The view a manager is given, and the tenant cell, on their own (design §4.31). */
class MemoryInternalsSpec extends AnyFlatSpec with Matchers with EitherValues {
  import MemoryTestSupport.*

  private val fact = Memory.userFact("the sky is blue")

  "A MemoryView" should "read the tenant's current store" in {
    val cell = new TenantMemory(InMemoryStore.withMemories(Seq(fact)).value)
    val view = MemoryView.reading(cell)
    view.get(fact.id).value.map(_.content) shouldBe Some("the sky is blue")
    view.count(MemoryFilter.All).value shouldBe 1L
    view.recent(5, MemoryFilter.All).value.map(_.id) shouldBe Seq(fact.id)
    view.search("sky", 5, MemoryFilter.All).value.map(_.memory.id) shouldBe Seq(fact.id)
  }

  it should "refuse every write but its mode's own" in {
    val cell    = new TenantMemory(InMemoryStore.empty)
    val reading = MemoryView.reading(cell)
    val refusals = Seq(
      reading.store(fact),
      reading.replace(Seq(fact), Nil),
      MemoryView.recording(cell).replace(Seq(fact), Nil),
      MemoryView.consolidating(cell)((_, _) => Right(())).store(fact),
      reading.delete(fact.id),
      reading.deleteMatching(MemoryFilter.All),
      reading.update(fact.id, identity),
      reading.clear()
    )
    refusals.foreach(_.left.value shouldBe a[ValidationError])
    contents(cell.current) shouldBe empty
  }

  it should "buffer what a recording manager stores, without writing it" in {
    val cell = new TenantMemory(InMemoryStore.empty)
    val view = MemoryView.recording(cell).storeAll(Seq(fact)).value.asInstanceOf[MemoryView]
    view.buffered.map(_.id) shouldBe Vector(fact.id)
    contents(cell.current) shouldBe empty
  }

  "A TenantMemory over a plain store" should "refuse a transactional write, forget nothing, hold no applied key" in {
    val cell = new TenantMemory(new PlainStore(InMemoryStore.empty))
    cell.transactional shouldBe false
    cell.write(s => Right(s -> ())).left.value.message should include("not a TransactionalMemoryStore")
    cell.forget(null, FencingToken(1)).isRight shouldBe true
    cell.appliedCount.value shouldBe 0
  }

  it should "keep the bound under its lock when it writes" in {
    val cell = new TenantMemory(new PlainStore(InMemoryStore.empty))
    val out = cell
      .writePlain(
        (1 to 4).map(i => Memory.userFact(s"fact $i").copy(timestamp = java.time.Instant.ofEpochSecond(i.toLong))),
        Nil,
        MemoryBounds(maxMemories = 2)
      )
      .value
    out.evicted shouldBe 2
    contents(cell.current) shouldBe Vector("fact 3", "fact 4")
    val removed =
      cell.writePlain(Nil, cell.current.recall().value.map(_.id) :+ MemoryId("absent"), MemoryBounds()).value
    removed.removed should have size 2
    contents(cell.current) shouldBe empty
  }

  "The outcome codec" should "refuse an outcome in a newer format" in {
    import MemoryEffects.given
    val newer = AppliedWrite.toJson(AppliedWrite(Vector.empty, Vector.empty, 0))
    newer("format") = AppliedWrite.Format + 1
    scala.util.Try(upickle.default.read[AppliedWrite](newer)).isFailure shouldBe true
    upickle.default.read[AppliedWrite](upickle.default.writeJs(AppliedWrite(Vector(fact.id), Vector.empty, 3))) shouldBe
      AppliedWrite(Vector(fact.id), Vector.empty, 3)
  }
}
