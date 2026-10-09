package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * The rule the SQL-backed stores share: SQL may narrow the rows a filter reads, but only by a condition that no
 * row `matches` accepts can fail, and `matches` decides whatever the SQL could not express exactly.
 */
class FilterSupportSpec extends AnyFlatSpec with Matchers {

  import FilterSupport.{ narrow, Sql }

  private val entity = MemoryFilter.ByEntity(EntityId("e1"))
  private val task   = MemoryFilter.ByType(MemoryType.Task)
  private val custom = MemoryFilter.Custom(_ => true)
  // A leaf SQL cannot express exactly: the store reads rows and lets `matches` decide.
  private val loose = MemoryFilter.ContentContains("x")

  private val leaf: MemoryFilter => Option[Sql] = {
    case MemoryFilter.ByEntity(e) => Some(Sql("entity_id = ?", Seq(e.value)))
    case MemoryFilter.ByType(t)   => Some(Sql("memory_type = ?", Seq(t.name)))
    case _                        => None
  }

  "narrow" should "express All as no restriction, exactly" in {
    val n = narrow(MemoryFilter.All)(leaf)
    n.sql shouldBe None
    n.exact shouldBe true
  }

  it should "express None as a condition no row satisfies, exactly" in {
    val n = narrow(MemoryFilter.None)(leaf)
    n.sql shouldBe Some(Sql("1 = 0", Seq.empty))
    n.exact shouldBe true
  }

  it should "never ask a store to translate a Custom, and read every row for it" in {
    val asked = new AtomicInteger(0)
    val n = narrow(custom) { f =>
      asked.incrementAndGet(); leaf(f)
    }
    n.sql shouldBe None
    n.exact shouldBe false
    asked.get shouldBe 0
  }

  it should "use the leaf's SQL as is, and call it exact" in {
    val n = narrow(entity)(leaf)
    n.sql shouldBe Some(Sql("entity_id = ?", Seq("e1")))
    n.exact shouldBe true
  }

  it should "treat a leaf the store cannot express as a read with no narrowing, decided by `matches`" in {
    val n = narrow(loose)(leaf)
    n.sql shouldBe None
    n.exact shouldBe false
  }

  it should "keep the narrowing conjunct of an And that contains a Custom, left or right" in {
    val left  = narrow(MemoryFilter.And(entity, custom))(leaf)
    val right = narrow(MemoryFilter.And(custom, entity))(leaf)
    left.sql shouldBe Some(Sql("entity_id = ?", Seq("e1")))
    right.sql shouldBe Some(Sql("entity_id = ?", Seq("e1")))
    left.exact shouldBe false
    right.exact shouldBe false
  }

  it should "join the two sides of an exact And and Or, keeping the parameters in order" in {
    narrow(MemoryFilter.And(entity, task))(leaf) shouldBe
      FilterSupport.Narrowing(Some(Sql("(entity_id = ?) AND (memory_type = ?)", Seq("e1", "task"))), exact = true)
    narrow(MemoryFilter.Or(task, entity))(leaf) shouldBe
      FilterSupport.Narrowing(Some(Sql("(memory_type = ?) OR (entity_id = ?)", Seq("task", "e1"))), exact = true)
  }

  it should "not narrow an Or when one side cannot be narrowed: the other side's rows are not the only ones" in {
    val n = narrow(MemoryFilter.Or(entity, custom))(leaf)
    n.sql shouldBe None
    n.exact shouldBe false
  }

  it should "treat an Or with an unrestricted side as unrestricted, and exact when both sides are" in {
    val n = narrow(MemoryFilter.Or(entity, MemoryFilter.All))(leaf)
    n.sql shouldBe None
    n.exact shouldBe true
  }

  it should "negate an exact filter" in {
    val n = narrow(MemoryFilter.Not(entity))(leaf)
    n.sql shouldBe Some(Sql("NOT (entity_id = ?)", Seq("e1")))
    n.exact shouldBe true
  }

  it should "negate All to a condition no row satisfies" in {
    narrow(MemoryFilter.Not(MemoryFilter.All))(leaf) shouldBe
      FilterSupport.Narrowing(Some(Sql("1 = 0", Seq.empty)), exact = true)
  }

  it should "not narrow through a Not of an inexact filter: the complement of a superset is a subset" in {
    // And(entity, custom) is a subset of entity, so NOT(entity) would drop rows that `Not(And(...))` accepts
    narrow(MemoryFilter.Not(MemoryFilter.And(entity, custom)))(leaf) shouldBe FilterSupport.Narrowing(
      None,
      exact = false
    )
    narrow(MemoryFilter.Not(loose))(leaf) shouldBe FilterSupport.Narrowing(None, exact = false)
    narrow(MemoryFilter.Not(MemoryFilter.Or(task, custom)))(leaf) shouldBe FilterSupport.Narrowing(None, exact = false)
  }

  it should "stay inexact all the way up from an inexact leaf, and exact when everything is exact" in {
    narrow(MemoryFilter.And(task, MemoryFilter.Or(entity, loose)))(leaf).exact shouldBe false
    narrow(MemoryFilter.And(task, MemoryFilter.Or(entity, task)))(leaf).exact shouldBe true
  }
}
