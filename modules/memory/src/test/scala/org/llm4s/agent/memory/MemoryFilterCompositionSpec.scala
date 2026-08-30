package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit

class MemoryFilterCompositionSpec extends AnyFlatSpec with Matchers {

  // -- fixtures --
  private val now     = Instant.now()
  private val hourAgo = now.minus(1, ChronoUnit.HOURS)
  private val dayAgo  = now.minus(1, ChronoUnit.DAYS)
  private val weekAgo = now.minus(7, ChronoUnit.DAYS)

  private val convRecent = Memory(
    MemoryId.generate(),
    "recent conversation",
    MemoryType.Conversation,
    Map("conversation_id" -> "conv-1"),
    hourAgo,
    importance = Some(0.8)
  )

  private val convOld = Memory(
    MemoryId.generate(),
    "old conversation",
    MemoryType.Conversation,
    Map("conversation_id" -> "conv-2"),
    weekAgo,
    importance = Some(0.3)
  )

  private val entityImportant = Memory(
    MemoryId.generate(),
    "important entity",
    MemoryType.Entity,
    Map("entity_id" -> "e-1", "source" -> "wiki"),
    hourAgo,
    importance = Some(0.9)
  )

  private val knowledgeLow = Memory(
    MemoryId.generate(),
    "low knowledge",
    MemoryType.Knowledge,
    Map("source" -> "book"),
    dayAgo,
    importance = Some(0.2)
  )

  private val userFact = Memory(
    MemoryId.generate(),
    "user prefers Scala",
    MemoryType.UserFact,
    Map("user_id" -> "u-1"),
    now,
    importance = Some(0.6)
  )

  private val all = Seq(convRecent, convOld, entityImportant, knowledgeLow, userFact)

  private def matching(filter: MemoryFilter): Seq[Memory] =
    all.filter(filter.matches)

  // ---- AND behavioral tests ----

  "A && B" should "return only memories matching both filters" in {
    val filter = MemoryFilter.ByType(MemoryType.Conversation) && MemoryFilter.MinImportance(0.5)
    matching(filter) shouldBe Seq(convRecent)
  }

  it should "return empty when no memory satisfies both" in {
    val filter = MemoryFilter.ByType(MemoryType.Knowledge) && MemoryFilter.MinImportance(0.5)
    matching(filter) shouldBe empty
  }

  // ---- OR behavioral tests ----

  "A || B" should "return memories matching either filter" in {
    val filter = MemoryFilter.ByType(MemoryType.Conversation) || MemoryFilter.ByType(MemoryType.Entity)
    matching(filter) should contain theSameElementsAs Seq(convRecent, convOld, entityImportant)
  }

  it should "not duplicate when a memory matches both sides" in {
    val filter = MemoryFilter.ByType(MemoryType.Entity) || MemoryFilter.MinImportance(0.5)
    // entityImportant matches both sides but appears once in the filtered list
    matching(filter) should contain theSameElementsAs Seq(convRecent, entityImportant, userFact)
  }

  // ---- NOT behavioral tests ----

  "!A" should "invert match results" in {
    val filter = !MemoryFilter.ByType(MemoryType.Conversation)
    matching(filter) should contain theSameElementsAs Seq(entityImportant, knowledgeLow, userFact)
  }

  // ---- Complex nesting ----

  "(A && B) || C" should "match memories satisfying either the conjunction or C" in {
    // (Conversation AND important) OR Knowledge
    val filter =
      (MemoryFilter.ByType(MemoryType.Conversation) && MemoryFilter.MinImportance(0.5)) ||
        MemoryFilter.ByType(MemoryType.Knowledge)
    matching(filter) should contain theSameElementsAs Seq(convRecent, knowledgeLow)
  }

  "A && (B || C)" should "match memories satisfying A and either B or C" in {
    // Important AND (Conversation OR Entity)
    val filter =
      MemoryFilter.MinImportance(0.5) &&
        (MemoryFilter.ByType(MemoryType.Conversation) || MemoryFilter.ByType(MemoryType.Entity))
    matching(filter) should contain theSameElementsAs Seq(convRecent, entityImportant)
  }

  "(A || B) && !C" should "match either A or B but not C" in {
    // (Conversation OR Entity) AND NOT important
    val filter =
      (MemoryFilter.ByType(MemoryType.Conversation) || MemoryFilter.ByType(MemoryType.Entity)) &&
        !MemoryFilter.MinImportance(0.5)
    matching(filter) should contain theSameElementsAs Seq(convOld)
  }

  // ---- Mixed filter types ----

  "Composing ByType + ByTimeRange + MinImportance" should "narrow results correctly" in {
    val filter =
      MemoryFilter.ByType(MemoryType.Conversation) &&
        MemoryFilter.ByTimeRange(after = Some(dayAgo)) &&
        MemoryFilter.MinImportance(0.5)
    matching(filter) shouldBe Seq(convRecent)
  }

  "Composing ByMetadata + ByType" should "filter on both axes" in {
    val filter =
      MemoryFilter.ByType(MemoryType.Entity) && MemoryFilter.HasMetadata("source")
    matching(filter) shouldBe Seq(entityImportant)
  }

  "ContentContains + ByType" should "compose correctly" in {
    val filter =
      MemoryFilter.ContentContains("scala") && MemoryFilter.ByType(MemoryType.UserFact)
    matching(filter) shouldBe Seq(userFact)
  }

  // ---- Custom with composition ----

  "Custom && ByType" should "compose correctly" in {
    val longContent = MemoryFilter.Custom(_.content.length > 10)
    val filter      = longContent && MemoryFilter.ByType(MemoryType.Conversation)
    matching(filter) should contain theSameElementsAs Seq(convRecent, convOld)
  }

  // ---- Identity laws ----

  "filter && All" should "behave like the original filter" in {
    val filter = MemoryFilter.ByType(MemoryType.Conversation)
    matching(filter && MemoryFilter.All) should contain theSameElementsAs matching(filter)
  }

  "filter || None" should "behave like the original filter" in {
    val filter = MemoryFilter.ByType(MemoryType.Entity)
    matching(filter || MemoryFilter.None) should contain theSameElementsAs matching(filter)
  }

  // ---- Absorption laws ----

  "filter || All" should "match everything" in {
    val filter = MemoryFilter.ByType(MemoryType.Conversation) || MemoryFilter.All
    matching(filter) should contain theSameElementsAs all
  }

  "filter && None" should "match nothing" in {
    val filter = MemoryFilter.ByType(MemoryType.Conversation) && MemoryFilter.None
    matching(filter) shouldBe empty
  }

  // ---- Double negation ----

  "!!filter" should "behave like the original filter" in {
    val filter = MemoryFilter.ByType(MemoryType.Conversation)
    val double = !(!filter)
    matching(double) should contain theSameElementsAs matching(filter)
  }

  // ---- De Morgan's laws ----

  "!(A && B)" should "be equivalent to !A || !B" in {
    val a = MemoryFilter.ByType(MemoryType.Conversation)
    val b = MemoryFilter.MinImportance(0.5)

    val deMorgan = !a || !b
    val negated  = !(a && b)
    matching(deMorgan) should contain theSameElementsAs matching(negated)
  }

  "!(A || B)" should "be equivalent to !A && !B" in {
    val a = MemoryFilter.ByType(MemoryType.Conversation)
    val b = MemoryFilter.ByType(MemoryType.Entity)

    val deMorgan = !a && !b
    val negated  = !(a || b)
    matching(deMorgan) should contain theSameElementsAs matching(negated)
  }

  // ---- Commutativity ----

  "A && B" should "produce same results as B && A" in {
    val a = MemoryFilter.ByType(MemoryType.Conversation)
    val b = MemoryFilter.MinImportance(0.5)
    matching(a && b) should contain theSameElementsAs matching(b && a)
  }

  "A || B" should "produce same results as B || A" in {
    val a = MemoryFilter.ByType(MemoryType.Conversation)
    val b = MemoryFilter.ByType(MemoryType.Entity)
    matching(a || b) should contain theSameElementsAs matching(b || a)
  }

  // ---- Associativity ----

  "(A && B) && C" should "produce same results as A && (B && C)" in {
    val a = MemoryFilter.ByType(MemoryType.Conversation)
    val b = MemoryFilter.MinImportance(0.5)
    val c = MemoryFilter.ByTimeRange(after = Some(dayAgo))

    matching((a && b) && c) should contain theSameElementsAs matching(a && (b && c))
  }

  "(A || B) || C" should "produce same results as A || (B || C)" in {
    val a = MemoryFilter.ByType(MemoryType.Conversation)
    val b = MemoryFilter.ByType(MemoryType.Entity)
    val c = MemoryFilter.ByType(MemoryType.Knowledge)

    matching((a || b) || c) should contain theSameElementsAs matching(a || (b || c))
  }

  // ---- all() / any() factory composition ----

  "MemoryFilter.all with empty args" should "return All (match everything)" in {
    matching(MemoryFilter.all()) should contain theSameElementsAs all
  }

  "MemoryFilter.any with empty args" should "return None (match nothing)" in {
    matching(MemoryFilter.any()) shouldBe empty
  }

  "MemoryFilter.all with single filter" should "behave like that filter" in {
    val filter = MemoryFilter.ByType(MemoryType.Entity)
    matching(MemoryFilter.all(filter)) should contain theSameElementsAs matching(filter)
  }

  "MemoryFilter.any with single filter" should "behave like that filter" in {
    val filter = MemoryFilter.ByType(MemoryType.Entity)
    matching(MemoryFilter.any(filter)) should contain theSameElementsAs matching(filter)
  }
}
