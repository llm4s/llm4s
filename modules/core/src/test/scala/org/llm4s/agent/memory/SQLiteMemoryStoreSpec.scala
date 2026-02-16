package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.time.Instant

class SQLiteMemoryStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private var store: SQLiteMemoryStore = _

  override def beforeEach(): Unit = {
    super.beforeEach()
    store = SQLiteMemoryStore.inMemory().toOption.get
  }

  override def afterEach(): Unit = {
    super.afterEach()
    if (store != null) store.close()
  }

  "SQLiteMemoryStore" should "store and retrieve a memory" in {
    val memory = Memory(
      id = MemoryId.generate(),
      content = "Test content",
      memoryType = MemoryType.Conversation
    )

    val result = for {
      _         <- store.store(memory)
      retrieved <- store.get(memory.id)
    } yield retrieved

    result.isRight shouldBe true
    val retrieved = result.toOption.get
    retrieved shouldBe defined
    retrieved.get.content shouldBe "Test content"
    retrieved.get.memoryType shouldBe MemoryType.Conversation
  }

  it should "return None for non-existent memory" in {
    val result = store.get(MemoryId("non-existent"))
    result shouldBe Right(None)
  }

  it should "update existing memory on store with same ID" in {
    val id     = MemoryId.generate()
    val memory = Memory(id, "Original content", MemoryType.Knowledge)

    val result = for {
      _         <- store.store(memory)
      _         <- store.store(memory.copy(content = "Updated content"))
      retrieved <- store.get(id)
    } yield retrieved

    result.isRight shouldBe true
    result.toOption.get.get.content shouldBe "Updated content"
  }

  it should "store and retrieve memory with all fields" in {
    val memory = Memory(
      id = MemoryId.generate(),
      content = "Full memory",
      memoryType = MemoryType.Entity,
      timestamp = Instant.parse("2024-01-15T10:30:00Z"),
      importance = Some(0.8),
      metadata = Map(
        "conversation_id" -> "conv-123",
        "entity_id"       -> "entity-456",
        "source"          -> "test-source",
        "key1"            -> "value1",
        "key2"            -> "value2"
      ),
      embedding = Some(Array(0.1f, 0.2f, 0.3f))
    )

    val result = for {
      _         <- store.store(memory)
      retrieved <- store.get(memory.id)
    } yield retrieved

    result.isRight shouldBe true
    val retrieved = result.toOption.get.get
    retrieved.content shouldBe "Full memory"
    retrieved.memoryType shouldBe MemoryType.Entity
    retrieved.importance shouldBe Some(0.8)
    retrieved.conversationId shouldBe Some("conv-123")
    retrieved.getMetadata("entity_id") shouldBe Some("entity-456")
    retrieved.source shouldBe Some("test-source")
    retrieved.metadata.get("key1") shouldBe Some("value1")
    retrieved.metadata.get("key2") shouldBe Some("value2")
    retrieved.embedding shouldBe defined
    (retrieved.embedding.get should have).length(3)
  }

  it should "recall memories by type" in {
    val conv   = Memory(MemoryId.generate(), "Conversation", MemoryType.Conversation)
    val entity = Memory(MemoryId.generate(), "Entity", MemoryType.Entity)
    val know   = Memory(MemoryId.generate(), "Knowledge", MemoryType.Knowledge)

    val result = for {
      _ <- store.store(conv)
      _ <- store.store(entity)
      _ <- store.store(know)
      r <- store.recall(MemoryFilter.ByType(MemoryType.Conversation))
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(1)
    memories.head.content shouldBe "Conversation"
  }

  it should "recall memories by conversation ID" in {
    val m1 = Memory(MemoryId.generate(), "Conv 1", MemoryType.Conversation)
      .withMetadata("conversation_id", "conv-a")
    val m2 = Memory(MemoryId.generate(), "Conv 2", MemoryType.Conversation)
      .withMetadata("conversation_id", "conv-a")
    val m3 = Memory(MemoryId.generate(), "Conv 3", MemoryType.Conversation)
      .withMetadata("conversation_id", "conv-b")

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(MemoryFilter.ByConversation("conv-a"))
    } yield r

    result.isRight shouldBe true
    (result.toOption.get should have).length(2)
  }

  it should "recall memories by entity ID" in {
    val entityId = EntityId.fromName("Test Entity")
    val m1       = Memory.forEntity(entityId, "Test Entity", "Fact 1", "test")
    val m2       = Memory.forEntity(entityId, "Test Entity", "Fact 2", "test")
    val m3       = Memory.forEntity(EntityId.fromName("Other"), "Other", "Fact 3", "test")

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(MemoryFilter.ByEntity(entityId))
    } yield r

    result.isRight shouldBe true
    (result.toOption.get should have).length(2)
  }

  it should "recall memories by time range" in {
    val t1 = Instant.parse("2024-01-01T00:00:00Z")
    val t2 = Instant.parse("2024-01-15T00:00:00Z")
    val t3 = Instant.parse("2024-02-01T00:00:00Z")

    val m1 = Memory(MemoryId.generate(), "Old", MemoryType.Knowledge).copy(timestamp = t1)
    val m2 = Memory(MemoryId.generate(), "Middle", MemoryType.Knowledge).copy(timestamp = t2)
    val m3 = Memory(MemoryId.generate(), "New", MemoryType.Knowledge).copy(timestamp = t3)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(
        MemoryFilter.ByTimeRange(
          after = Some(Instant.parse("2024-01-10T00:00:00Z")),
          before = Some(Instant.parse("2024-01-20T00:00:00Z"))
        )
      )
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(1)
    memories.head.content shouldBe "Middle"
  }

  it should "recall memories by minimum importance" in {
    val m1 = Memory(MemoryId.generate(), "Low", MemoryType.Knowledge).withImportance(0.3)
    val m2 = Memory(MemoryId.generate(), "Medium", MemoryType.Knowledge).withImportance(0.6)
    val m3 = Memory(MemoryId.generate(), "High", MemoryType.Knowledge).withImportance(0.9)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(MemoryFilter.MinImportance(0.5))
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(2)
    (memories.map(_.content) should contain).allOf("Medium", "High")
  }

  it should "recall memories with metadata filter" in {
    val m1 = Memory(MemoryId.generate(), "Has key", MemoryType.Knowledge)
      .withMetadata("role", "user")
    val m2 = Memory(MemoryId.generate(), "Different value", MemoryType.Knowledge)
      .withMetadata("role", "assistant")
    val m3 = Memory(MemoryId.generate(), "No key", MemoryType.Knowledge)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(MemoryFilter.ByMetadata("role", "user"))
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(1)
    memories.head.content shouldBe "Has key"
  }

  it should "support combined filters with And" in {
    val m1 = Memory(MemoryId.generate(), "Match both", MemoryType.Conversation)
      .withImportance(0.8)
    val m2 = Memory(MemoryId.generate(), "Match type only", MemoryType.Conversation)
      .withImportance(0.3)
    val m3 = Memory(MemoryId.generate(), "Match importance only", MemoryType.Knowledge)
      .withImportance(0.8)

    val filter = MemoryFilter.ByType(MemoryType.Conversation) && MemoryFilter.MinImportance(0.5)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(filter)
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(1)
    memories.head.content shouldBe "Match both"
  }

  it should "support combined filters with Or" in {
    val m1 = Memory(MemoryId.generate(), "Conversation", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "Entity", MemoryType.Entity)
    val m3 = Memory(MemoryId.generate(), "Knowledge", MemoryType.Knowledge)

    val filter = MemoryFilter.ByType(MemoryType.Conversation) || MemoryFilter.ByType(MemoryType.Entity)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recall(filter)
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(2)
    (memories.map(_.memoryType) should contain).allOf(MemoryType.Conversation, MemoryType.Entity)
  }

  it should "support Not filter" in {
    val m1 = Memory(MemoryId.generate(), "Conversation", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "Knowledge", MemoryType.Knowledge)

    val filter = !MemoryFilter.ByType(MemoryType.Conversation)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      r <- store.recall(filter)
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(1)
    memories.head.memoryType shouldBe MemoryType.Knowledge
  }

  it should "search memories using full-text search" in {
    val m1 = Memory(MemoryId.generate(), "Scala is a programming language", MemoryType.Knowledge)
    val m2 = Memory(MemoryId.generate(), "Python is popular for ML", MemoryType.Knowledge)
    val m3 = Memory(MemoryId.generate(), "Scala runs on the JVM", MemoryType.Knowledge)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.search("Scala programming")
    } yield r

    result.isRight shouldBe true
    val scored = result.toOption.get
    scored should not be empty
    scored.head.memory.content should include("Scala")
    scored.foreach(s => s.score should be >= 0.0)
    scored.foreach(s => s.score should be <= 1.0)
  }

  it should "delete a memory" in {
    val memory = Memory(MemoryId.generate(), "To delete", MemoryType.Knowledge)

    val result = for {
      _         <- store.store(memory)
      _         <- store.delete(memory.id)
      retrieved <- store.get(memory.id)
    } yield retrieved

    result shouldBe Right(None)
  }

  it should "delete matching memories" in {
    val m1 = Memory(MemoryId.generate(), "Keep", MemoryType.Knowledge)
    val m2 = Memory(MemoryId.generate(), "Delete 1", MemoryType.Conversation)
    val m3 = Memory(MemoryId.generate(), "Delete 2", MemoryType.Conversation)

    val result = for {
      _     <- store.store(m1)
      _     <- store.store(m2)
      _     <- store.store(m3)
      _     <- store.deleteMatching(MemoryFilter.ByType(MemoryType.Conversation))
      count <- store.count()
    } yield count

    result shouldBe Right(1L)
  }

  it should "deleteMatching deletes correctly for compound SQL filters" in {
    val m1 = Memory(MemoryId.generate(), "Keep low", MemoryType.Conversation).withImportance(0.3)
    val m2 = Memory(MemoryId.generate(), "Delete high conv", MemoryType.Conversation).withImportance(0.8)
    val m3 = Memory(MemoryId.generate(), "Keep knowledge", MemoryType.Knowledge).withImportance(0.8)

    val filter = MemoryFilter.ByType(MemoryType.Conversation) && MemoryFilter.MinImportance(0.5)

    val result = for {
      _        <- store.store(m1)
      _        <- store.store(m2)
      _        <- store.store(m3)
      _        <- store.deleteMatching(filter)
      count    <- store.count()
      recalled <- store.recall()
    } yield (count, recalled.map(_.content).toSet)

    result.isRight shouldBe true
    val (count, contents) = result.toOption.get
    count shouldBe 2L
    contents shouldBe Set("Keep low", "Keep knowledge")
  }

  it should "deleteMatching cleans up FTS entries" in {
    val m1 = Memory(MemoryId.generate(), "searchable unique phrase alpha", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "keep this memory", MemoryType.Knowledge)

    val result = for {
      _            <- store.store(m1)
      _            <- store.store(m2)
      beforeSearch <- store.search("alpha")
      _            <- store.deleteMatching(MemoryFilter.ByType(MemoryType.Conversation))
      afterSearch  <- store.search("alpha")
      count        <- store.count()
    } yield (beforeSearch.length, afterSearch.length, count)

    result.isRight shouldBe true
    val (beforeLen, afterLen, count) = result.toOption.get
    beforeLen shouldBe 1
    afterLen shouldBe 0
    count shouldBe 1L
  }

  it should "deleteMatching with Custom filter uses safe fallback" in {
    val m1 = Memory(MemoryId.generate(), "Match custom", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "No match", MemoryType.Knowledge)

    val customFilter = MemoryFilter.Custom(_.content.contains("custom"))

    val result = for {
      _        <- store.store(m1)
      _        <- store.store(m2)
      _        <- store.deleteMatching(customFilter)
      count    <- store.count()
      recalled <- store.recall()
    } yield (count, recalled)

    result.isRight shouldBe true
    val (count, recalled) = result.toOption.get
    count shouldBe 1L
    recalled.map(_.content) shouldBe Seq("No match")
  }

  it should "deleteMatching with nested Custom filter uses safe fallback" in {
    val m1 = Memory(MemoryId.generate(), "Match custom conv", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "Plain conversation", MemoryType.Conversation)
    val m3 = Memory(MemoryId.generate(), "Match custom know", MemoryType.Knowledge)

    // Nested Custom inside And: Custom && ByType
    val nestedFilter = MemoryFilter.Custom(_.content.contains("custom")) &&
      MemoryFilter.ByType(MemoryType.Conversation)

    val result = for {
      _        <- store.store(m1)
      _        <- store.store(m2)
      _        <- store.store(m3)
      _        <- store.deleteMatching(nestedFilter)
      count    <- store.count()
      recalled <- store.recall()
    } yield (count, recalled.map(_.content).toSet)

    result.isRight shouldBe true
    val (count, contents) = result.toOption.get
    count shouldBe 2L
    contents shouldBe Set("Plain conversation", "Match custom know")
  }

  it should "deleteMatching with Custom inside Or uses safe fallback" in {
    val m1 = Memory(MemoryId.generate(), "Match custom", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "Entity type", MemoryType.Entity)
    val m3 = Memory(MemoryId.generate(), "Knowledge type", MemoryType.Knowledge)

    // Custom inside Or: Custom || ByType(Entity) should match m1 and m2
    val orFilter = MemoryFilter.Custom(_.content.contains("custom")) ||
      MemoryFilter.ByType(MemoryType.Entity)

    val result = for {
      _        <- store.store(m1)
      _        <- store.store(m2)
      _        <- store.store(m3)
      _        <- store.deleteMatching(orFilter)
      count    <- store.count()
      recalled <- store.recall()
    } yield (count, recalled.map(_.content).toSet)

    result.isRight shouldBe true
    val (count, contents) = result.toOption.get
    count shouldBe 1L
    contents shouldBe Set("Knowledge type")
  }

  it should "deleteMatching with Custom inside Not uses safe fallback" in {
    val m1 = Memory(MemoryId.generate(), "Keep this custom", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "Delete me", MemoryType.Knowledge)

    // Not(Custom): should delete memories that do NOT contain "custom"
    val notFilter = !MemoryFilter.Custom(_.content.contains("custom"))

    val result = for {
      _        <- store.store(m1)
      _        <- store.store(m2)
      _        <- store.deleteMatching(notFilter)
      count    <- store.count()
      recalled <- store.recall()
    } yield (count, recalled.map(_.content).toSet)

    result.isRight shouldBe true
    val (count, contents) = result.toOption.get
    count shouldBe 1L
    contents shouldBe Set("Keep this custom")
  }

  it should "deleteMatching with MemoryFilter.All falls back to safe row-by-row" in {
    val m1 = Memory(MemoryId.generate(), "Memory 1", MemoryType.Conversation)
    val m2 = Memory(MemoryId.generate(), "Memory 2", MemoryType.Knowledge)

    // MemoryFilter.All produces empty WHERE, should use safe fallback
    val result = for {
      _     <- store.store(m1)
      _     <- store.store(m2)
      _     <- store.deleteMatching(MemoryFilter.All)
      count <- store.count()
    } yield count

    result shouldBe Right(0L)
  }

  it should "update a memory" in {
    val memory = Memory(MemoryId.generate(), "Original", MemoryType.Knowledge)

    val result = for {
      _         <- store.store(memory)
      _         <- store.update(memory.id, _.copy(content = "Updated"))
      retrieved <- store.get(memory.id)
    } yield retrieved

    result.isRight shouldBe true
    result.toOption.get.get.content shouldBe "Updated"
  }

  it should "return error when updating non-existent memory" in {
    val result = store.update(MemoryId("non-existent"), _.copy(content = "Updated"))
    result.isLeft shouldBe true
  }

  it should "count all memories" in {
    val m1 = Memory(MemoryId.generate(), "One", MemoryType.Knowledge)
    val m2 = Memory(MemoryId.generate(), "Two", MemoryType.Knowledge)
    val m3 = Memory(MemoryId.generate(), "Three", MemoryType.Conversation)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      c <- store.count()
    } yield c

    result shouldBe Right(3L)
  }

  it should "count memories with filter" in {
    val m1 = Memory(MemoryId.generate(), "One", MemoryType.Knowledge)
    val m2 = Memory(MemoryId.generate(), "Two", MemoryType.Knowledge)
    val m3 = Memory(MemoryId.generate(), "Three", MemoryType.Conversation)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      c <- store.count(MemoryFilter.ByType(MemoryType.Knowledge))
    } yield c

    result shouldBe Right(2L)
  }

  it should "clear all memories" in {
    val m1 = Memory(MemoryId.generate(), "One", MemoryType.Knowledge)
    val m2 = Memory(MemoryId.generate(), "Two", MemoryType.Conversation)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.clear()
      c <- store.count()
    } yield c

    result shouldBe Right(0L)
  }

  it should "get recent memories in order" in {
    val t1 = Instant.parse("2024-01-01T00:00:00Z")
    val t2 = Instant.parse("2024-01-02T00:00:00Z")
    val t3 = Instant.parse("2024-01-03T00:00:00Z")

    val m1 = Memory(MemoryId.generate(), "Oldest", MemoryType.Knowledge).copy(timestamp = t1)
    val m2 = Memory(MemoryId.generate(), "Middle", MemoryType.Knowledge).copy(timestamp = t2)
    val m3 = Memory(MemoryId.generate(), "Newest", MemoryType.Knowledge).copy(timestamp = t3)

    val result = for {
      _ <- store.store(m1)
      _ <- store.store(m2)
      _ <- store.store(m3)
      r <- store.recent(2)
    } yield r

    result.isRight shouldBe true
    val memories = result.toOption.get
    (memories should have).length(2)
    memories.head.content shouldBe "Newest"
    memories(1).content shouldBe "Middle"
  }

  it should "respect limit in recall" in {
    val memories = (1 to 10).map(i => Memory(MemoryId.generate(), s"Memory $i", MemoryType.Knowledge))

    val result = for {
      _ <- memories.foldLeft[org.llm4s.types.Result[MemoryStore]](Right(store))((acc, m) => acc.flatMap(_.store(m)))
      r <- store.recall(limit = 5)
    } yield r

    result.isRight shouldBe true
    (result.toOption.get should have).length(5)
  }

  it should "handle custom memory types" in {
    val memory = Memory(MemoryId.generate(), "Custom", MemoryType.Custom("my-type"))

    val result = for {
      _         <- store.store(memory)
      retrieved <- store.get(memory.id)
    } yield retrieved

    result.isRight shouldBe true
    result.toOption.get.get.memoryType shouldBe MemoryType.Custom("my-type")
  }

  it should "handle special characters in content" in {
    val content = "Special: \"quotes\", 'apostrophes', \nnewlines,\ttabs"
    val memory  = Memory(MemoryId.generate(), content, MemoryType.Knowledge)

    val result = for {
      _         <- store.store(memory)
      retrieved <- store.get(memory.id)
    } yield retrieved

    result.isRight shouldBe true
    result.toOption.get.get.content shouldBe content
  }

  it should "handle special characters in metadata" in {
    val memory = Memory(MemoryId.generate(), "Test", MemoryType.Knowledge)
      .withMetadata("key with spaces", "value with \"quotes\"")

    val result = for {
      _         <- store.store(memory)
      retrieved <- store.get(memory.id)
    } yield retrieved

    result.isRight shouldBe true
    val metadata = result.toOption.get.get.metadata
    metadata.get("key with spaces") shouldBe Some("value with \"quotes\"")
  }

  "SQLiteMemoryStore.apply" should "create store with file path" in {
    val tempFile = java.io.File.createTempFile("test-memory", ".db")
    tempFile.deleteOnExit()

    val result = SQLiteMemoryStore(tempFile.getAbsolutePath)
    result.isRight shouldBe true
    result.toOption.get.close()
  }
}
