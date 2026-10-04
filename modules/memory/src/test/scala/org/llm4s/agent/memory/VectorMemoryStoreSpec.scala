package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.nio.file.{ Files, Path }
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Comparator
import scala.util.Using

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

/**
 * Persistence across close/reopen on a file-backed [[VectorMemoryStore]].
 *
 * The suite above only uses `inMemory()`, and `SQLiteMemoryStoreSpec` checks that a store can be
 * created on a path, so neither shows that data written by one instance is readable by another.
 */
class VectorMemoryStoreFilePersistenceSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val embeddings = MockEmbeddingService.default

  private var tempDir: Path = _

  override def beforeEach(): Unit =
    tempDir = Files.createTempDirectory("llm4s-vector-store-persistence")

  override def afterEach(): Unit =
    if (tempDir != null) {
      Using.resource(Files.walk(tempDir))(paths =>
        paths.sorted(Comparator.reverseOrder[Path]()).forEach(p => Files.delete(p))
      )
    }

  private def dbPath: String = tempDir.resolve("memories.db").toString

  /** Open a store on the shared file, run `f`, and always close it again. */
  private def withStore[A](f: VectorMemoryStore => A): A = withStoreAt(dbPath, embeddings)(f)

  private def withStoreAt[A](path: String, service: EmbeddingService)(f: VectorMemoryStore => A): A = {
    val opened = VectorMemoryStore(path, service).fold(
      e => fail(s"Failed to open file-backed store: ${e.message}"),
      identity
    )
    Using.resource(new AutoCloseable { override def close(): Unit = opened.close() })(_ => f(opened))
  }

  private val base = Instant.ofEpochMilli(1700000000000L)

  private val first = Memory(
    id = MemoryId("persist-1"),
    content = "The user prefers Scala over Java",
    memoryType = MemoryType.UserFact,
    metadata = Map("user_id" -> "user-a", "note" -> "with spaces"),
    timestamp = base,
    importance = Some(0.9)
  )

  private val second = Memory(
    id = MemoryId("persist-2"),
    content = "Distributed databases are the user's specialty",
    memoryType = MemoryType.Knowledge,
    metadata = Map("source" -> "docs"),
    timestamp = base.plusSeconds(60),
    importance = None
  )

  "A file-backed VectorMemoryStore" should "return all fields of stored memories from a second instance" in {
    withStore { s =>
      s.store(first).isRight shouldBe true
      s.store(second).isRight shouldBe true
    }

    withStore { s =>
      s.count() shouldBe Right(2L)

      val one = s.get(first.id).toOption.flatten.getOrElse(fail("persist-1 missing after reopen"))
      one.content shouldBe first.content
      one.memoryType shouldBe MemoryType.UserFact
      one.importance shouldBe Some(0.9)
      one.timestamp shouldBe base
      one.getMetadata("user_id") shouldBe Some("user-a")
      one.getMetadata("note") shouldBe Some("with spaces")

      val two = s.get(second.id).toOption.flatten.getOrElse(fail("persist-2 missing after reopen"))
      two.content shouldBe second.content
      two.memoryType shouldBe MemoryType.Knowledge
      two.importance shouldBe None
      two.getMetadata("source") shouldBe Some("docs")
    }
  }

  it should "persist the generated embeddings so they survive a reopen" in {
    withStore(_.store(first))

    withStore { s =>
      val embedding = s.get(first.id).toOption.flatten.flatMap(_.embedding).getOrElse(fail("embedding not persisted"))
      val expected  = embeddings.embed(first.content).getOrElse(fail("mock embed failed"))
      embedding.toSeq shouldBe expected.toSeq

      val stats = s.vectorStats.getOrElse(fail("vectorStats failed"))
      stats.totalMemories shouldBe 1
      stats.embeddedMemories shouldBe 1
      s.embedAll() shouldBe Right(0)
    }
  }

  it should "run semantic search and filtered recall over data written by a previous instance" in {
    withStore { s =>
      s.store(first)
      s.store(second)
    }

    withStore { s =>
      val hits = s.search(second.content, topK = 2).getOrElse(fail("search failed"))
      hits.map(_.memory.id) shouldBe Seq(second.id, first.id)
      hits.head.score should be > hits(1).score

      s.recall(MemoryFilter.ByType(MemoryType.UserFact)).toOption.get.map(_.id) shouldBe Seq(first.id)
      s.recall(MemoryFilter.MinImportance(0.8)).toOption.get.map(_.id) shouldBe Seq(first.id)
    }
  }

  it should "append writes made after a reopen without losing earlier data" in {
    withStore(_.store(first))

    withStore { s =>
      s.count() shouldBe Right(1L)
      s.store(second).isRight shouldBe true
    }

    withStore { s =>
      s.count() shouldBe Right(2L)
      // most recent first
      s.recent(10).toOption.get.map(_.id) shouldBe Seq(second.id, first.id)
    }
  }

  it should "persist updates and deletes made in a later session" in {
    withStore { s =>
      s.store(first)
      s.store(second)
    }

    withStore { s =>
      s.update(first.id, _.copy(content = "The user now prefers Kotlin")).isRight shouldBe true
      s.delete(second.id).isRight shouldBe true
    }

    withStore { s =>
      s.count() shouldBe Right(1L)
      s.get(second.id) shouldBe Right(None)
      val updated = s.get(first.id).toOption.flatten.getOrElse(fail("persist-1 missing"))
      updated.content shouldBe "The user now prefers Kotlin"
      // content changed, so the stored embedding was regenerated and persisted too
      updated.embedding.map(_.toSeq) shouldBe embeddings.embed("The user now prefers Kotlin").toOption.map(_.toSeq)
    }
  }

  it should "empty the file on clear so a later instance sees no memories" in {
    withStore { s =>
      s.store(first)
      s.clear().isRight shouldBe true
    }

    withStore(_.count() shouldBe Right(0L))
  }

  it should "round-trip a memory with no metadata and importance bounds 0.0 and 1.0" in {
    val low  = Memory(MemoryId("low"), "lowest", MemoryType.Task, Map.empty, base, Some(0.0))
    val high = Memory(MemoryId("high"), "highest", MemoryType.Task, Map.empty, base.plusSeconds(1), Some(1.0))
    withStore { s =>
      s.store(low)
      s.store(high)
    }
    withStore { s =>
      val readLow = s.get(low.id).toOption.flatten.getOrElse(fail("low missing"))
      readLow.metadata shouldBe empty
      readLow.importance shouldBe Some(0.0)
      s.get(high.id).toOption.flatten.flatMap(_.importance) shouldBe Some(1.0)
    }
  }

  it should "filter by conversation, entity and metadata over data written by a previous instance" in {
    val a = first.copy(
      id = MemoryId("a"),
      metadata = Map("conversation_id" -> "conv-1", "entity_id" -> "ent-1", "topic" -> "scala")
    )
    val b = second.copy(
      id = MemoryId("b"),
      metadata = Map("conversation_id" -> "conv-2", "entity_id" -> "ent-2", "topic" -> "java")
    )
    withStore { s =>
      s.store(a)
      s.store(b)
    }
    withStore { s =>
      s.recall(MemoryFilter.ByConversation("conv-2")).toOption.get.map(_.id) shouldBe Seq(b.id)
      s.recall(MemoryFilter.ByEntity(EntityId("ent-1"))).toOption.get.map(_.id) shouldBe Seq(a.id)
      s.recall(MemoryFilter.ByMetadata("topic", "java")).toOption.get.map(_.id) shouldBe Seq(b.id)
      s.recall(MemoryFilter.HasMetadata("topic")).toOption.get.map(_.id.value).toSet shouldBe Set("a", "b")
      s.recall(MemoryFilter.ByTimeRange(Some(base.plusSeconds(30)), None)).toOption.get.map(_.id) shouldBe Seq(b.id)
    }
  }

  it should "keep a caller-supplied embedding instead of regenerating it" in {
    val custom = Array.tabulate(embeddings.dimensions)(i => (i % 7).toFloat / 7f)
    withStore(_.store(first.withEmbedding(custom)))
    withStore { s =>
      val read = s.get(first.id).toOption.flatten.flatMap(_.embedding).getOrElse(fail("embedding missing"))
      read.toSeq shouldBe custom.toSeq
    }
  }

  it should "replace, not duplicate, a memory stored twice with the same id across sessions" in {
    withStore(_.store(first))
    withStore(_.store(first.copy(content = "replaced content")))
    withStore { s =>
      s.count() shouldBe Right(1L)
      s.get(first.id).toOption.flatten.map(_.content) shouldBe Some("replaced content")
      s.search("replaced content", topK = 5).toOption.get.map(_.memory.id) shouldBe Seq(first.id)
    }
  }

  it should "work on a path containing spaces and non-ASCII characters" in {
    val dir = Files.createDirectory(tempDir.resolve("dir with spaces \u00fcn\u00efcode"))
    val db  = dir.resolve("m\u00e9moires.db")
    withStoreAt(db.toString, embeddings)(_.store(first))
    withStoreAt(db.toString, embeddings) { s =>
      s.get(first.id).toOption.flatten.map(_.content) shouldBe Some(first.content)
    }
  }

  it should "return a Left instead of throwing when the file is not a database" in {
    val bad = tempDir.resolve("garbage.db")
    Files.write(bad, Array.fill[Byte](4096)(0x5a.toByte))
    VectorMemoryStore(bad.toString, embeddings).isLeft shouldBe true
  }

  it should "keep a single schema version row across repeated reopens" in {
    withStore(_.store(first))
    withStore(_.count())
    withStore(_.count())

    Using.resource(java.sql.DriverManager.getConnection(s"jdbc:sqlite:$dbPath")) { c =>
      Using.resource(c.createStatement()) { st =>
        Using.resource(st.executeQuery("SELECT COUNT(*), MIN(version), MAX(version) FROM schema_version")) { rs =>
          rs.next() shouldBe true
          (rs.getInt(1), rs.getInt(2), rs.getInt(3)) shouldBe ((1, 1, 1))
        }
      }
    }
  }

  it should "still answer search without throwing when reopened with a different embedding dimension" in {
    withStore(_.store(first))

    withStoreAt(dbPath, MockEmbeddingService(dimensions = 64)) { other =>
      // The stored 1536-dim vector cannot be compared with a 64-dim query: the store must degrade
      // (keyword fallback) rather than throw or return a bogus ranking.
      other.search("Scala", topK = 3).isRight shouldBe true
      other.get(first.id).toOption.flatten.map(_.content) shouldBe Some(first.content)
    }
  }

  it should "lose no writes when two instances write to the same file concurrently" in {
    import java.util.concurrent.{ CountDownLatch, Executors }
    import scala.concurrent.{ Await, ExecutionContext, Future }
    import scala.concurrent.duration._

    val perWriter = 25
    val pool      = Executors.newFixedThreadPool(2)
    Using.resource(new AutoCloseable { override def close(): Unit = pool.shutdown() }) { _ =>
      withStore { a =>
        withStore { b =>
          implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
          val start                         = new CountDownLatch(1)
          def writer(store: VectorMemoryStore, prefix: String): Future[Seq[Boolean]] = Future {
            start.await()
            (1 to perWriter).map { i =>
              store
                .store(Memory(MemoryId(s"$prefix-$i"), s"$prefix content $i", MemoryType.Task, Map.empty, base))
                .isRight
            }
          }
          val fa = writer(a, "a")
          val fb = writer(b, "b")
          start.countDown()
          val outcomes = Await.result(Future.sequence(Seq(fa, fb)), 120.seconds).flatten

          outcomes.forall(identity) shouldBe true
          a.count() shouldBe Right((2 * perWriter).toLong)
          b.count() shouldBe Right((2 * perWriter).toLong)
        }
      }
    }
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
