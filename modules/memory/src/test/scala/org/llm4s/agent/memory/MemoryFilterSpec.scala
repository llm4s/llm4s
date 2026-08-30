package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.time.temporal.ChronoUnit

class MemoryFilterSpec extends AnyFlatSpec with Matchers {

  // Test fixtures
  val now: Instant       = Instant.now()
  val hourAgo: Instant   = now.minus(1, ChronoUnit.HOURS)
  val dayAgo: Instant    = now.minus(1, ChronoUnit.DAYS)
  val weekAgo: Instant   = now.minus(7, ChronoUnit.DAYS)
  val hourLater: Instant = now.plus(1, ChronoUnit.HOURS)

  def conversationMemory(content: String, timestamp: Instant = now): Memory =
    Memory(
      MemoryId.generate(),
      content,
      MemoryType.Conversation,
      Map("conversation_id" -> "conv-1", "role" -> "user"),
      timestamp
    )

  def entityMemory(entityId: String, content: String): Memory =
    Memory(
      MemoryId.generate(),
      content,
      MemoryType.Entity,
      Map("entity_id" -> entityId, "entity_name" -> "Test Entity"),
      now
    )

  def knowledgeMemory(content: String, source: String): Memory =
    Memory(
      MemoryId.generate(),
      content,
      MemoryType.Knowledge,
      Map("source" -> source),
      now
    )

  "MemoryFilter.All" should "match all memories" in {
    val memory = conversationMemory("Test")
    MemoryFilter.All.matches(memory) shouldBe true
  }

  "MemoryFilter.None" should "match no memories" in {
    val memory = conversationMemory("Test")
    MemoryFilter.None.matches(memory) shouldBe false
  }

  "MemoryFilter.ByType" should "filter by memory type" in {
    val convMemory   = conversationMemory("Test")
    val entityMem    = entityMemory("e-1", "Test")
    val knowledgeMem = knowledgeMemory("Test", "source")

    val filter = MemoryFilter.ByType(MemoryType.Conversation)
    filter.matches(convMemory) shouldBe true
    filter.matches(entityMem) shouldBe false
    filter.matches(knowledgeMem) shouldBe false
  }

  "MemoryFilter.ByTypes" should "filter by multiple memory types" in {
    val convMemory   = conversationMemory("Test")
    val entityMem    = entityMemory("e-1", "Test")
    val knowledgeMem = knowledgeMemory("Test", "source")

    val filter = MemoryFilter.ByTypes(Set(MemoryType.Conversation, MemoryType.Entity))
    filter.matches(convMemory) shouldBe true
    filter.matches(entityMem) shouldBe true
    filter.matches(knowledgeMem) shouldBe false
  }

  "MemoryFilter.ByMetadata" should "filter by exact metadata match" in {
    val memory1 = conversationMemory("Test").withMetadata("key", "value1")
    val memory2 = conversationMemory("Test").withMetadata("key", "value2")
    val memory3 = conversationMemory("Test") // no key

    val filter = MemoryFilter.ByMetadata("key", "value1")
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
    filter.matches(memory3) shouldBe false
  }

  "MemoryFilter.HasMetadata" should "filter by metadata key existence" in {
    val memory1 = conversationMemory("Test").withMetadata("key", "value")
    val memory2 = conversationMemory("Test")

    val filter = MemoryFilter.HasMetadata("key")
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
  }

  "MemoryFilter.MetadataContains" should "filter by metadata substring" in {
    val memory1 = conversationMemory("Test").withMetadata("desc", "hello world")
    val memory2 = conversationMemory("Test").withMetadata("desc", "goodbye")

    val filter = MemoryFilter.MetadataContains("desc", "world")
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
  }

  "MemoryFilter.ByEntity" should "filter by entity ID" in {
    val memory1 = entityMemory("entity-1", "Fact 1")
    val memory2 = entityMemory("entity-2", "Fact 2")

    val filter = MemoryFilter.ByEntity(EntityId("entity-1"))
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
  }

  "MemoryFilter.ByConversation" should "filter by conversation ID" in {
    val memory1 = conversationMemory("Test")
    val memory2 = Memory(
      MemoryId.generate(),
      "Other",
      MemoryType.Conversation,
      Map("conversation_id" -> "conv-2"),
      now
    )

    val filter = MemoryFilter.ByConversation("conv-1")
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
  }

  "MemoryFilter.ByTimeRange" should "filter by timestamp range" in {
    val oldMemory    = conversationMemory("Old", weekAgo)
    val recentMemory = conversationMemory("Recent", hourAgo)
    val newMemory    = conversationMemory("New", now)

    val afterFilter = MemoryFilter.ByTimeRange(after = Some(dayAgo))
    afterFilter.matches(oldMemory) shouldBe false
    afterFilter.matches(recentMemory) shouldBe true
    afterFilter.matches(newMemory) shouldBe true

    val beforeFilter = MemoryFilter.ByTimeRange(before = Some(dayAgo))
    beforeFilter.matches(oldMemory) shouldBe true
    beforeFilter.matches(recentMemory) shouldBe false
    beforeFilter.matches(newMemory) shouldBe false

    val rangeFilter = MemoryFilter.ByTimeRange(after = Some(weekAgo), before = Some(hourAgo))
    rangeFilter.matches(oldMemory) shouldBe true
    rangeFilter.matches(recentMemory) shouldBe true
    rangeFilter.matches(newMemory) shouldBe false
  }

  "MemoryFilter.MinImportance" should "filter by importance threshold" in {
    val lowImportance  = conversationMemory("Test").withImportance(0.3)
    val highImportance = conversationMemory("Test").withImportance(0.8)
    val noImportance   = conversationMemory("Test")

    val filter = MemoryFilter.MinImportance(0.5)
    filter.matches(lowImportance) shouldBe false
    filter.matches(highImportance) shouldBe true
    filter.matches(noImportance) shouldBe false
  }

  "MemoryFilter.ContentContains" should "filter by content substring" in {
    val memory1 = conversationMemory("Hello World")
    val memory2 = conversationMemory("Goodbye")

    val filter = MemoryFilter.ContentContains("world")
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
  }

  it should "be case-sensitive when configured" in {
    val memory1 = conversationMemory("Hello World")
    val memory2 = conversationMemory("hello world")

    val filter = MemoryFilter.ContentContains("World", caseSensitive = true)
    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
  }

  "MemoryFilter.And" should "combine filters with AND logic" in {
    val memory1 = conversationMemory("Test").withImportance(0.8)
    val memory2 = conversationMemory("Test").withImportance(0.3)
    val memory3 = entityMemory("e-1", "Test").withImportance(0.8)

    val filter = MemoryFilter.And(
      MemoryFilter.ByType(MemoryType.Conversation),
      MemoryFilter.MinImportance(0.5)
    )

    filter.matches(memory1) shouldBe true
    filter.matches(memory2) shouldBe false
    filter.matches(memory3) shouldBe false
  }

  "MemoryFilter.Or" should "combine filters with OR logic" in {
    val convMemory   = conversationMemory("Test")
    val entityMem    = entityMemory("e-1", "Test")
    val knowledgeMem = knowledgeMemory("Test", "source")

    val filter = MemoryFilter.Or(
      MemoryFilter.ByType(MemoryType.Conversation),
      MemoryFilter.ByType(MemoryType.Entity)
    )

    filter.matches(convMemory) shouldBe true
    filter.matches(entityMem) shouldBe true
    filter.matches(knowledgeMem) shouldBe false
  }

  "MemoryFilter.Not" should "negate a filter" in {
    val convMemory = conversationMemory("Test")
    val entityMem  = entityMemory("e-1", "Test")

    val filter = MemoryFilter.Not(MemoryFilter.ByType(MemoryType.Conversation))

    filter.matches(convMemory) shouldBe false
    filter.matches(entityMem) shouldBe true
  }

  "MemoryFilter operators" should "support && operator" in {
    val filter = MemoryFilter.conversations && MemoryFilter.important(0.5)
    filter shouldBe a[MemoryFilter.And]
  }

  it should "support || operator" in {
    val filter = MemoryFilter.conversations || MemoryFilter.entities
    filter shouldBe a[MemoryFilter.Or]
  }

  it should "support unary_! operator" in {
    val filter = !MemoryFilter.conversations
    filter shouldBe a[MemoryFilter.Not]
  }

  "MemoryFilter.Custom" should "support custom predicates" in {
    val filter = MemoryFilter.Custom(m => m.content.length > 10)

    filter.matches(conversationMemory("Short")) shouldBe false
    filter.matches(conversationMemory("This is a longer message")) shouldBe true
  }

  "MemoryFilter convenience methods" should "provide shorthand filters" in {
    val convMemory   = conversationMemory("Test")
    val entityMem    = entityMemory("e-1", "Test")
    val knowledgeMem = knowledgeMemory("Test", "source")
    val userFactMem  = Memory(MemoryId.generate(), "Fact", MemoryType.UserFact, Map.empty, now)
    val taskMem      = Memory(MemoryId.generate(), "Task", MemoryType.Task, Map.empty, now)

    MemoryFilter.conversations.matches(convMemory) shouldBe true
    MemoryFilter.entities.matches(entityMem) shouldBe true
    MemoryFilter.knowledge.matches(knowledgeMem) shouldBe true
    MemoryFilter.userFacts.matches(userFactMem) shouldBe true
    MemoryFilter.tasks.matches(taskMem) shouldBe true
  }

  "MemoryFilter.all" should "combine multiple filters with AND" in {
    val filter = MemoryFilter.all(
      MemoryFilter.conversations,
      MemoryFilter.important(0.5),
      MemoryFilter.after(dayAgo)
    )

    val goodMemory = conversationMemory("Test", hourAgo).withImportance(0.7)
    val badMemory1 = conversationMemory("Test", weekAgo).withImportance(0.7)
    val badMemory2 = conversationMemory("Test", hourAgo).withImportance(0.3)

    filter.matches(goodMemory) shouldBe true
    filter.matches(badMemory1) shouldBe false
    filter.matches(badMemory2) shouldBe false
  }

  "MemoryFilter.any" should "combine multiple filters with OR" in {
    val filter = MemoryFilter.any(
      MemoryFilter.conversations,
      MemoryFilter.entities
    )

    filter.matches(conversationMemory("Test")) shouldBe true
    filter.matches(entityMemory("e-1", "Test")) shouldBe true
    filter.matches(knowledgeMemory("Test", "source")) shouldBe false
  }

  it should "return None filter for empty sequence" in {
    val filter = MemoryFilter.any()
    filter.matches(conversationMemory("Test")) shouldBe false
  }
}
