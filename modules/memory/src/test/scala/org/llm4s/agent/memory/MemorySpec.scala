package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class MemorySpec extends AnyFlatSpec with Matchers {

  "MemoryId" should "generate unique IDs" in {
    val id1 = MemoryId.generate()
    val id2 = MemoryId.generate()

    id1 should not be id2
    id1.value should not be empty
    id2.value should not be empty
  }

  it should "have string representation equal to value" in {
    val id = MemoryId("test-id-123")
    id.toString shouldBe "test-id-123"
    id.value shouldBe "test-id-123"
  }

  "EntityId" should "generate unique IDs" in {
    val id1 = EntityId.generate()
    val id2 = EntityId.generate()

    id1 should not be id2
  }

  it should "normalize names correctly" in {
    EntityId.fromName("John Doe").value shouldBe "john_doe"
    EntityId.fromName("UPPER CASE").value shouldBe "upper_case"
    EntityId.fromName("  extra   spaces  ").value shouldBe "_extra_spaces_"
  }

  "MemoryType" should "parse built-in types from string" in {
    MemoryType.fromString("conversation") shouldBe MemoryType.Conversation
    MemoryType.fromString("entity") shouldBe MemoryType.Entity
    MemoryType.fromString("knowledge") shouldBe MemoryType.Knowledge
    MemoryType.fromString("user_fact") shouldBe MemoryType.UserFact
    MemoryType.fromString("task") shouldBe MemoryType.Task
  }

  it should "handle case-insensitive parsing" in {
    MemoryType.fromString("CONVERSATION") shouldBe MemoryType.Conversation
    MemoryType.fromString("Entity") shouldBe MemoryType.Entity
  }

  it should "create custom types for unknown strings" in {
    MemoryType.fromString("custom_type") shouldBe MemoryType.Custom("custom_type")
    MemoryType.fromString("my-special-type") shouldBe MemoryType.Custom("my-special-type")
  }

  "Memory" should "be created with required fields" in {
    val memory = Memory(
      id = MemoryId.generate(),
      content = "Test content",
      memoryType = MemoryType.Conversation
    )

    memory.content shouldBe "Test content"
    memory.memoryType shouldBe MemoryType.Conversation
    memory.metadata shouldBe empty
    memory.importance shouldBe None
    memory.embedding shouldBe None
  }

  it should "support metadata operations" in {
    val memory = Memory(
      id = MemoryId.generate(),
      content = "Test",
      memoryType = MemoryType.Entity
    )

    val withKey = memory.withMetadata("key1", "value1")
    withKey.metadata shouldBe Map("key1" -> "value1")

    val withMultiple = withKey.withMetadata(Map("key2" -> "value2", "key3" -> "value3"))
    withMultiple.metadata shouldBe Map("key1" -> "value1", "key2" -> "value2", "key3" -> "value3")

    withMultiple.getMetadata("key1") shouldBe Some("value1")
    withMultiple.getMetadata("nonexistent") shouldBe None
  }

  it should "clamp importance scores to [0.0, 1.0]" in {
    val memory = Memory(MemoryId.generate(), "Test", MemoryType.Knowledge)

    memory.withImportance(0.5).importance shouldBe Some(0.5)
    memory.withImportance(-0.5).importance shouldBe Some(0.0)
    memory.withImportance(1.5).importance shouldBe Some(1.0)
  }

  it should "track embedding status" in {
    val memory = Memory(MemoryId.generate(), "Test", MemoryType.Knowledge)

    memory.isEmbedded shouldBe false

    val embedded = memory.withEmbedding(Array(0.1f, 0.2f, 0.3f))
    embedded.isEmbedded shouldBe true
    (embedded.embedding.get should have).length(3)
  }

  "Memory factory methods" should "create conversation memory" in {
    val memory = Memory.fromConversation("Hello, how can I help?", "assistant", Some("conv-123"))

    memory.memoryType shouldBe MemoryType.Conversation
    memory.content shouldBe "Hello, how can I help?"
    memory.getMetadata("role") shouldBe Some("assistant")
    memory.conversationId shouldBe Some("conv-123")
  }

  it should "create entity memory" in {
    val entityId = EntityId.fromName("John Doe")
    val memory   = Memory.forEntity(entityId, "John Doe", "Works at Anthropic", "person")

    memory.memoryType shouldBe MemoryType.Entity
    memory.content shouldBe "Works at Anthropic"
    memory.getMetadata("entity_id") shouldBe Some(entityId.value)
    memory.getMetadata("entity_name") shouldBe Some("John Doe")
    memory.getMetadata("entity_type") shouldBe Some("person")
  }

  it should "create knowledge memory" in {
    val memory = Memory.fromKnowledge("Scala is a JVM language", "docs/scala.md", Some(5))

    memory.memoryType shouldBe MemoryType.Knowledge
    memory.source shouldBe Some("docs/scala.md")
    memory.getMetadata("chunk_index") shouldBe Some("5")
  }

  it should "create user fact memory" in {
    val memory = Memory.userFact("Prefers dark mode", Some("user-456"))

    memory.memoryType shouldBe MemoryType.UserFact
    memory.content shouldBe "Prefers dark mode"
    memory.getMetadata("user_id") shouldBe Some("user-456")
  }

  it should "create task memory" in {
    val memory = Memory.fromTask("Deploy to production", "Successfully deployed", success = true)

    memory.memoryType shouldBe MemoryType.Task
    memory.content should include("Deploy to production")
    memory.content should include("Successfully deployed")
    memory.getMetadata("success") shouldBe Some("true")
  }
}
