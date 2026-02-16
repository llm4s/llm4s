package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.time.Instant
import java.time.temporal.ChronoUnit

class VectorMemoryStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  var store: VectorMemoryStore = _

  override def beforeEach(): Unit =
    store = VectorMemoryStore.inMemory().getOrElse(fail("Failed to create store"))

  override def afterEach(): Unit =
    if (store != null) store.close()

  // ===== Basic Operations =====

  "VectorMemoryStore" should "store and retrieve a memory with embedding" in {
    val memory = Memory(
      id = MemoryId("test-1"),
      content = "Test content for embedding",
      memoryType = MemoryType.Knowledge
    )

    val result = store.store(memory)
    result.isRight shouldBe true

    val retrieved = store.get(MemoryId("test-1"))
    retrieved.isRight shouldBe true
    retrieved.toOption.get.isDefined shouldBe true

    val mem = retrieved.toOption.get.get
    mem.content shouldBe "Test content for embedding"
    mem.embedding.isDefined shouldBe true // Should have been embedded
  }

  it should "return None for non-existent memory" in {
    val result = store.get(MemoryId("non-existent"))
    result shouldBe Right(None)
  }

  it should "preserve existing embedding when storing" in {
    val existingEmbedding = Array.fill(1536)(0.5f)
    val memory = Memory(
      id = MemoryId("test-embed"),
      content = "Test content",
      memoryType = MemoryType.Knowledge,
      embedding = Some(existingEmbedding)
    )

    store.store(memory)
    val retrieved = store.get(MemoryId("test-embed")).toOption.get.get

    retrieved.embedding.isDefined shouldBe true
    retrieved.embedding.get.length shouldBe 1536
    retrieved.embedding.get.head shouldBe 0.5f
  }

  // ===== Semantic Search =====

  it should "perform semantic search using embeddings" in {
    // Store some memories
    val memories = Seq(
      Memory.fromKnowledge("Scala is a programming language", "docs"),
      Memory.fromKnowledge("Python is used for machine learning", "docs"),
      Memory.fromKnowledge("Java runs on the JVM", "docs")
    )

    memories.foreach(m => store.store(m))

    // Search for programming-related content
    val results = store.search("programming languages", topK = 3)
    results.isRight shouldBe true
    results.toOption.get.length should be > 0

    // Results should be scored between 0 and 1
    results.toOption.get.foreach { scored =>
      scored.score should be >= 0.0
      scored.score should be <= 1.0
    }
  }

  it should "return top-K results sorted by similarity" in {
    // Store several memories
    (1 to 10).foreach(i => store.store(Memory.fromKnowledge(s"Content number $i about topic", "docs")))

    val results = store.search("content topic", topK = 5)
    results.isRight shouldBe true
    results.toOption.get.length shouldBe 5

    // Results should be sorted by score descending
    val scores = results.toOption.get.map(_.score)
    scores shouldBe scores.sorted.reverse
  }

  it should "apply filter during semantic search" in {
    store.store(Memory.fromKnowledge("Important knowledge", "important-source"))
    store.store(Memory.fromKnowledge("Regular knowledge", "other-source"))

    val results = store.search(
      "knowledge",
      topK = 10,
      filter = MemoryFilter.ByMetadata("source", "important-source")
    )

    results.isRight shouldBe true
    results.toOption.get.length shouldBe 1
    results.toOption.get.head.memory.getMetadata("source") shouldBe Some("important-source")
  }

  // ===== Recall with Filters =====

  it should "recall memories by type" in {
    store.store(Memory.fromKnowledge("Knowledge content", "docs"))
    store.store(Memory.fromConversation("Hello", "user", Some("conv-1")))

    val knowledge = store.recall(MemoryFilter.ByType(MemoryType.Knowledge))
    knowledge.toOption.get.length shouldBe 1
    knowledge.toOption.get.head.memoryType shouldBe MemoryType.Knowledge

    val conversations = store.recall(MemoryFilter.ByType(MemoryType.Conversation))
    conversations.toOption.get.length shouldBe 1
  }

  it should "recall memories by conversation ID" in {
    store.store(Memory.fromConversation("Message 1", "user", Some("conv-1")))
    store.store(Memory.fromConversation("Message 2", "assistant", Some("conv-1")))
    store.store(Memory.fromConversation("Message 3", "user", Some("conv-2")))

    val conv1 = store.recall(MemoryFilter.ByConversation("conv-1"))
    conv1.toOption.get.length shouldBe 2

    val conv2 = store.recall(MemoryFilter.ByConversation("conv-2"))
    conv2.toOption.get.length shouldBe 1
  }

  it should "recall memories by entity ID" in {
    val scalaId = EntityId.fromName("Scala")
    store.store(Memory.forEntity(scalaId, "Scala", "A programming language", "technology"))
    store.store(Memory.forEntity(scalaId, "Scala", "Created by Odersky", "technology"))
    store.store(Memory.forEntity(EntityId.fromName("Python"), "Python", "Another language", "technology"))

    val scalaMemories = store.recall(MemoryFilter.ByEntity(scalaId))
    scalaMemories.toOption.get.length shouldBe 2
  }

  it should "recall memories by time range" in {
    val now       = Instant.now()
    val yesterday = now.minus(1, ChronoUnit.DAYS)
    val lastWeek  = now.minus(7, ChronoUnit.DAYS)

    store.store(Memory.fromKnowledge("Recent", "docs").copy(timestamp = now))
    store.store(Memory.fromKnowledge("Yesterday", "docs").copy(timestamp = yesterday))
    store.store(Memory.fromKnowledge("Old", "docs").copy(timestamp = lastWeek))

    val recent = store.recall(MemoryFilter.after(yesterday.minus(1, ChronoUnit.HOURS)))
    recent.toOption.get.length shouldBe 2
  }

  it should "recall memories by minimum importance" in {
    store.store(Memory.fromKnowledge("Important", "docs").withImportance(0.9))
    store.store(Memory.fromKnowledge("Medium", "docs").withImportance(0.5))
    store.store(Memory.fromKnowledge("Low", "docs").withImportance(0.1))

    val important = store.recall(MemoryFilter.MinImportance(0.8))
    important.toOption.get.length shouldBe 1
    important.toOption.get.head.importance shouldBe Some(0.9)
  }

  // ===== CRUD Operations =====

  it should "delete a memory" in {
    val memory = Memory.fromKnowledge("To delete", "docs")
    store.store(memory)

    store.get(memory.id).toOption.get.isDefined shouldBe true

    store.delete(memory.id)
    store.get(memory.id).toOption.get.isDefined shouldBe false
  }

  it should "delete matching memories" in {
    store.store(Memory.fromKnowledge("Knowledge 1", "docs"))
    store.store(Memory.fromKnowledge("Knowledge 2", "docs"))
    store.store(Memory.fromConversation("Message", "user", None))

    store.count().toOption.get shouldBe 3

    store.deleteMatching(MemoryFilter.ByType(MemoryType.Knowledge))
    store.count().toOption.get shouldBe 1
  }

  it should "update a memory" in {
    val memory = Memory.fromKnowledge("Original", "docs")
    store.store(memory)

    store.update(memory.id, _.copy(content = "Updated"))

    val updated = store.get(memory.id).toOption.get.get
    updated.content shouldBe "Updated"
  }

  it should "re-embed when content changes on update" in {
    val memory = Memory.fromKnowledge("Original content", "docs")
    store.store(memory)

    val original          = store.get(memory.id).toOption.get.get
    val originalEmbedding = original.embedding.get

    store.update(memory.id, _.copy(content = "Completely different content"))

    val updated          = store.get(memory.id).toOption.get.get
    val updatedEmbedding = updated.embedding.get

    // Embeddings should be different since content changed
    // (MockEmbeddingService generates deterministic embeddings based on content hash)
    (originalEmbedding should not).equal(updatedEmbedding)
  }

  // ===== Utility Operations =====

  it should "count all memories" in {
    store.count().toOption.get shouldBe 0

    store.store(Memory.fromKnowledge("1", "docs"))
    store.store(Memory.fromKnowledge("2", "docs"))

    store.count().toOption.get shouldBe 2
  }

  it should "count memories with filter" in {
    store.store(Memory.fromKnowledge("Knowledge", "docs"))
    store.store(Memory.fromConversation("Message", "user", None))

    store.count(MemoryFilter.ByType(MemoryType.Knowledge)).toOption.get shouldBe 1
    store.count(MemoryFilter.ByType(MemoryType.Conversation)).toOption.get shouldBe 1
  }

  it should "clear all memories" in {
    store.store(Memory.fromKnowledge("1", "docs"))
    store.store(Memory.fromKnowledge("2", "docs"))
    store.count().toOption.get shouldBe 2

    store.clear()
    store.count().toOption.get shouldBe 0
  }

  it should "get recent memories in order" in {
    val now = Instant.now()
    (1 to 5).foreach { i =>
      store.store(Memory.fromKnowledge(s"Memory $i", "docs").copy(timestamp = now.minus(i, ChronoUnit.HOURS)))
    }

    val recent = store.recent(3)
    recent.toOption.get.length shouldBe 3
    recent.toOption.get.head.content shouldBe "Memory 1" // Most recent
    recent.toOption.get.last.content shouldBe "Memory 3"
  }

  // ===== Vector-Specific Features =====

  it should "embed all memories without embeddings" in {
    // Store memories without embeddings (by storing directly with SQL or using a store that doesn't auto-embed)
    // Since VectorMemoryStore auto-embeds, this tests the embedAll method on already-embedded memories
    store.store(Memory.fromKnowledge("Content 1", "docs"))
    store.store(Memory.fromKnowledge("Content 2", "docs"))

    val result = store.embedAll()
    result.isRight shouldBe true
    result.toOption.get shouldBe 0 // All already embedded
  }

  it should "provide vector statistics" in {
    store.store(Memory.fromKnowledge("Content 1", "docs"))
    store.store(Memory.fromKnowledge("Content 2", "docs"))

    val stats = store.vectorStats
    stats.isRight shouldBe true

    val s = stats.toOption.get
    s.totalMemories shouldBe 2
    s.embeddedMemories shouldBe 2
    s.embeddingCoverage shouldBe 100.0
    s.embeddingDimensions.contains(1536) shouldBe true // MockEmbeddingService default
  }

  // ===== Combined Filters =====

  it should "support AND filters" in {
    store.store(Memory.fromKnowledge("Important knowledge", "docs").withImportance(0.9))
    store.store(Memory.fromKnowledge("Regular knowledge", "docs").withImportance(0.3))
    store.store(Memory.fromConversation("Important message", "user", None).withImportance(0.9))

    val filter  = MemoryFilter.ByType(MemoryType.Knowledge) && MemoryFilter.MinImportance(0.8)
    val results = store.recall(filter)

    results.toOption.get.length shouldBe 1
    results.toOption.get.head.content should include("Important knowledge")
  }

  it should "support OR filters" in {
    store.store(Memory.fromKnowledge("Knowledge", "docs"))
    store.store(Memory.fromConversation("Message", "user", None))
    store.store(Memory.userFact("User likes Scala", None))

    val filter  = MemoryFilter.ByType(MemoryType.Knowledge) || MemoryFilter.ByType(MemoryType.UserFact)
    val results = store.recall(filter)

    results.toOption.get.length shouldBe 2
  }

  it should "support NOT filters" in {
    store.store(Memory.fromKnowledge("Knowledge", "docs"))
    store.store(Memory.fromConversation("Message", "user", None))

    val filter  = !MemoryFilter.ByType(MemoryType.Conversation)
    val results = store.recall(filter)

    results.toOption.get.length shouldBe 1
    results.toOption.get.head.memoryType shouldBe MemoryType.Knowledge
  }

  // ===== Edge Cases =====

  it should "handle empty search query" in {
    store.store(Memory.fromKnowledge("Some content", "docs"))

    val results = store.search("", topK = 5)
    results.isRight shouldBe true
    // Empty query should still work with mock embeddings
  }

  it should "handle special characters in content" in {
    val memory = Memory.fromKnowledge("""Content with "quotes" and 'apostrophes' and \backslash""", "docs")
    store.store(memory)

    val retrieved = store.get(memory.id).toOption.get.get
    retrieved.content should include("quotes")
    retrieved.content should include("apostrophes")
  }

  it should "handle metadata with special characters" in {
    val memory = Memory
      .fromKnowledge("Content", "docs")
      .withMetadata("key", "value with spaces")
      .withMetadata("path", "/some/path")

    store.store(memory)

    val retrieved = store.get(memory.id).toOption.get.get
    retrieved.getMetadata("key") shouldBe Some("value with spaces")
    retrieved.getMetadata("path") shouldBe Some("/some/path")
  }

  it should "handle custom memory types" in {
    val memory = Memory(
      id = MemoryId.generate(),
      content = "Custom content",
      memoryType = MemoryType.Custom("my_custom_type")
    )

    store.store(memory)
    val results = store.recall(MemoryFilter.ByType(MemoryType.Custom("my_custom_type")))

    results.toOption.get.length shouldBe 1
    results.toOption.get.head.memoryType shouldBe MemoryType.Custom("my_custom_type")
  }
}

class EmbeddingServiceSpec extends AnyFlatSpec with Matchers {

  "MockEmbeddingService" should "generate embeddings with correct dimensions" in {
    val service = MockEmbeddingService(1536)

    val result = service.embed("Test text")
    result.isRight shouldBe true
    result.toOption.get.length shouldBe 1536
  }

  it should "generate normalized unit vectors" in {
    val service   = MockEmbeddingService(100)
    val embedding = service.embed("Test text").toOption.get

    val magnitude = math.sqrt(embedding.map(x => x.toDouble * x).sum)
    magnitude shouldBe (1.0 +- 0.001)
  }

  it should "generate deterministic embeddings for same content" in {
    val service = MockEmbeddingService(100)

    val embedding1 = service.embed("Test text").toOption.get
    val embedding2 = service.embed("Test text").toOption.get

    embedding1 shouldBe embedding2
  }

  it should "generate different embeddings for different content" in {
    val service = MockEmbeddingService(100)

    val embedding1 = service.embed("First text").toOption.get
    val embedding2 = service.embed("Second text").toOption.get

    (embedding1 should not).equal(embedding2)
  }

  it should "batch embed multiple texts" in {
    val service = MockEmbeddingService(100)

    val result = service.embedBatch(Seq("Text 1", "Text 2", "Text 3"))
    result.isRight shouldBe true
    result.toOption.get.length shouldBe 3
  }
}

class VectorOpsSpec extends AnyFlatSpec with Matchers {

  "VectorOps.cosineSimilarity" should "return 1.0 for identical vectors" in {
    val v = Array(1.0f, 2.0f, 3.0f)
    VectorOps.cosineSimilarity(v, v) shouldBe (1.0 +- 0.001)
  }

  it should "return -1.0 for opposite vectors" in {
    val v1 = Array(1.0f, 0.0f, 0.0f)
    val v2 = Array(-1.0f, 0.0f, 0.0f)
    VectorOps.cosineSimilarity(v1, v2) shouldBe (-1.0 +- 0.001)
  }

  it should "return 0.0 for orthogonal vectors" in {
    val v1 = Array(1.0f, 0.0f)
    val v2 = Array(0.0f, 1.0f)
    VectorOps.cosineSimilarity(v1, v2) shouldBe (0.0 +- 0.001)
  }

  "VectorOps.euclideanDistance" should "return 0.0 for identical vectors" in {
    val v = Array(1.0f, 2.0f, 3.0f)
    VectorOps.euclideanDistance(v, v) shouldBe (0.0 +- 0.001)
  }

  it should "calculate correct distance" in {
    val v1 = Array(0.0f, 0.0f)
    val v2 = Array(3.0f, 4.0f)
    VectorOps.euclideanDistance(v1, v2) shouldBe (5.0 +- 0.001)
  }

  "VectorOps.normalize" should "create unit vector" in {
    val v          = Array(3.0f, 4.0f)
    val normalized = VectorOps.normalize(v)

    val magnitude = math.sqrt(normalized.map(x => x.toDouble * x).sum)
    magnitude shouldBe (1.0 +- 0.001)
  }

  "VectorOps.topKBySimilarity" should "return top-K most similar items" in {
    val query = Array(1.0f, 0.0f, 0.0f)
    val candidates = Seq(
      (Array(1.0f, 0.0f, 0.0f), "identical"),
      (Array(0.9f, 0.1f, 0.0f), "very similar"),
      (Array(0.0f, 1.0f, 0.0f), "orthogonal"),
      (Array(-1.0f, 0.0f, 0.0f), "opposite")
    )

    val results = VectorOps.topKBySimilarity(query, candidates, 2)

    results.length shouldBe 2
    results.head._1 shouldBe "identical"
    results(1)._1 shouldBe "very similar"
  }

  it should "return 0.0 for vectors containing NaN" in {
    val v1 = Array(1.0f, Float.NaN, 0.0f)
    val v2 = Array(1.0f, 0.0f, 0.0f)
    VectorOps.cosineSimilarity(v1, v2) shouldBe 0.0
  }

  it should "return 0.0 for vectors containing positive infinity" in {
    val v1 = Array(Float.PositiveInfinity, 0.0f, 0.0f)
    val v2 = Array(1.0f, 0.0f, 0.0f)
    VectorOps.cosineSimilarity(v1, v2) shouldBe 0.0
  }

  it should "return 0.0 for vectors containing negative infinity" in {
    val v1 = Array(Float.NegativeInfinity, 0.0f, 0.0f)
    val v2 = Array(1.0f, 0.0f, 0.0f)
    VectorOps.cosineSimilarity(v1, v2) shouldBe 0.0
  }

  it should "return 0.0 when both vectors contain non-finite values" in {
    val v1 = Array(Float.NaN, Float.PositiveInfinity)
    val v2 = Array(Float.NegativeInfinity, Float.NaN)
    VectorOps.cosineSimilarity(v1, v2) shouldBe 0.0
  }
}
