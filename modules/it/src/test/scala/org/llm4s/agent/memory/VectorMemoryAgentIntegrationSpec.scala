package org.llm4s.agent.memory

import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.File
import java.time.Instant
import java.util.UUID
import scala.util.Try

/**
 * Integration tests that wire VectorMemoryStore into a full Agent run and verify
 * that memories recorded in conversation turn N are retrieved and injected into
 * the context of turn N+1.
 *
 * Requirements:
 *  - pgvector (`PGVECTOR_TEST_URL`) for persistence-backed tests
 *  - Ollama (`OLLAMA_AVAILABLE=true`) for embedding-backed tests
 *
 * Each test uses `assume()` to skip gracefully when services are unavailable.
 *
 * To run locally:
 *   1. Start pgvector:
 *        docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16
 *   2. Start Ollama and pull an embedding model:
 *        ollama pull nomic-embed-text
 *   3. Export env vars:
 *        export PGVECTOR_TEST_URL="jdbc:postgresql://localhost:5432/postgres"
 *        export OLLAMA_AVAILABLE=true
 *   4. Run:
 *        sbt "it/testOnly org.llm4s.agent.memory.VectorMemoryAgentIntegrationSpec"
 */
class VectorMemoryAgentIntegrationSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // --- Environment guards (stored in vals to avoid calling sys.env inside test logic) ---

  private val pgvectorUrlEnv     = Option(System.getenv("PGVECTOR_TEST_URL")).filter(_.nonEmpty)
  private val ollamaAvailableEnv = Option(System.getenv("OLLAMA_AVAILABLE")).filter(v => v == "true" || v == "1")

  // Derived flags (lazy so they do not block test discovery)
  private lazy val pgvectorAvailable: Boolean = pgvectorUrlEnv.isDefined
  private lazy val ollamaAvailable: Boolean   = ollamaAvailableEnv.isDefined

  // Shared SQLite temp-file path per test (cleaned up in afterEach)
  private var tempDbFile: Option[File] = None

  // --- Lifecycle ---

  override def afterEach(): Unit = {
    tempDbFile.foreach { f =>
      if (f.exists()) { f.delete(); () }
    }
    tempDbFile = None
  }

  // --- Helpers ---

  /** Return a VectorMemoryStore backed by a fresh in-memory SQLite database. */
  private def freshInMemoryStore(embeddingService: EmbeddingService): VectorMemoryStore =
    VectorMemoryStore.inMemory(embeddingService).fold(
      e => fail(s"Failed to create in-memory VectorMemoryStore: ${e.message}"),
      identity
    )

  /** Return a VectorMemoryStore backed by a temp SQLite file (for cross-instance persistence tests). */
  private def freshFileStore(embeddingService: EmbeddingService): VectorMemoryStore = {
    val f    = File.createTempFile(s"llm4s-vms-${UUID.randomUUID()}", ".db")
    tempDbFile = Some(f)
    VectorMemoryStore(f.getAbsolutePath, embeddingService).fold(
      e => fail(s"Failed to create file-backed VectorMemoryStore: ${e.message}"),
      identity
    )
  }

  /** Open a second VectorMemoryStore connection to the same file path. */
  private def reopenFileStore(file: File, embeddingService: EmbeddingService): VectorMemoryStore =
    VectorMemoryStore(file.getAbsolutePath, embeddingService).fold(
      e => fail(s"Failed to reopen file-backed VectorMemoryStore: ${e.message}"),
      identity
    )

  /** The deterministic mock embedding service used across tests that do not require Ollama. */
  private val mockEmbeddingService: EmbeddingService = MockEmbeddingService.default

  // -----------------------------------------------------------------------
  // Test group 1: Record and retrieve — uses MockEmbeddingService (no Ollama required)
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (record-and-retrieve)" should
    "store 5 user facts via SimpleMemoryManager and retrieve them by query" in {
      val vectorStore = freshInMemoryStore(mockEmbeddingService)
      val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

      val facts = Seq(
        ("The user prefers Scala over Java", Some("user-a")),
        ("The user works on distributed systems", Some("user-a")),
        ("The user dislikes verbose code", Some("user-a")),
        ("The user uses IntelliJ IDEA as their IDE", Some("user-a")),
        ("The user has 10 years of JVM experience", Some("user-a"))
      )

      val finalManager = facts.foldLeft[MemoryManager](manager) { case (mgr, (fact, userId)) =>
        mgr.recordUserFact(fact, userId, importance = Some(0.8)).fold(
          e => fail(s"Failed to record fact '$fact': ${e.message}"),
          identity
        )
      }

      val contextResult = finalManager.getRelevantContext("What programming language does the user prefer?")
      contextResult.isRight shouldBe true

      val context = contextResult.toOption.get
      context should not be empty
      context should include("Scala")

      vectorStore.close()
    }

  it should "return an empty context when no relevant facts are stored" in {
    val vectorStore = freshInMemoryStore(mockEmbeddingService)
    val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

    val contextResult = manager.getRelevantContext("Tell me about Scala")
    contextResult.isRight shouldBe true
    contextResult.toOption.get shouldBe empty

    vectorStore.close()
  }

  it should "retrieve all stored memories via recall" in {
    val vectorStore = freshInMemoryStore(mockEmbeddingService)
    val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

    val m1 = manager.recordUserFact("User loves FP", Some("user-a"), Some(0.9)).fold(
      e => fail(s"recordUserFact failed: ${e.message}"),
      identity
    )
    val m2 = m1.recordUserFact("User loves cats library", Some("user-a"), Some(0.8)).fold(
      e => fail(s"recordUserFact 2 failed: ${e.message}"),
      identity
    )

    val allMemories = m2.store.recall(MemoryFilter.All)
    allMemories.isRight shouldBe true
    allMemories.toOption.get should have size 2

    vectorStore.close()
  }

  // -----------------------------------------------------------------------
  // Test group 2: Multi-turn memory injection into agent context
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (multi-turn)" should
    "carry turn-1 memories into turn-3 context via VectorMemoryStore" in {
      val vectorStore  = freshInMemoryStore(mockEmbeddingService)
      var manager: MemoryManager = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

      // Turn 1 — record a distinctive fact
      val turn1Fact = "The user is an expert in Apache Kafka"
      manager = manager.recordUserFact(turn1Fact, Some("turn-test-user"), Some(0.95)).fold(
        e => fail(s"Turn 1 recordUserFact failed: ${e.message}"),
        identity
      )

      // Turn 2 — record another fact
      manager = manager.recordUserFact("The user prefers exactly-once semantics", Some("turn-test-user"), Some(0.85)).fold(
        e => fail(s"Turn 2 recordUserFact failed: ${e.message}"),
        identity
      )

      // Turn 3 — query context and verify turn-1 memory is present
      val contextResult = manager.getRelevantContext("What streaming platform does the user know?")
      contextResult.isRight shouldBe true

      val context = contextResult.toOption.get
      context should not be empty
      context should include("Kafka")

      vectorStore.close()
    }

  it should "accumulate facts across multiple turns in chronological order" in {
    val vectorStore  = freshInMemoryStore(mockEmbeddingService)
    var manager: MemoryManager = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

    val turnFacts = Seq(
      "Turn 1: user is a backend engineer",
      "Turn 2: user works in the fintech domain",
      "Turn 3: user uses PostgreSQL as their primary database"
    )

    turnFacts.foreach { fact =>
      manager = manager.recordUserFact(fact, Some("multi-turn-user"), Some(0.8)).fold(
        e => fail(s"Failed to record fact '$fact': ${e.message}"),
        identity
      )
    }

    val totalCount = manager.store.count(MemoryFilter.All)
    totalCount.isRight shouldBe true
    totalCount.toOption.get shouldBe 3L

    vectorStore.close()
  }

  // -----------------------------------------------------------------------
  // Test group 3: User isolation — uses MockEmbeddingService (no Ollama required)
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (user-isolation)" should
    "not return user-B facts when querying for user-A context" in {
      val vectorStore = freshInMemoryStore(mockEmbeddingService)
      val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

      val m1 = manager.recordUserFact("user-A loves Scala and functional programming", Some("user-a"), Some(0.9)).fold(
        e => fail(s"user-A recordUserFact failed: ${e.message}"),
        identity
      )
      val m2 = m1.recordUserFact("user-B loves Python and machine learning", Some("user-b"), Some(0.9)).fold(
        e => fail(s"user-B recordUserFact failed: ${e.message}"),
        identity
      )

      // Filter for user-A only
      val userAFilter  = MemoryFilter.ByType(MemoryType.UserFact).and(MemoryFilter.ByMetadata("user_id", "user-a"))
      val userAResults = m2.store.recall(userAFilter)

      userAResults.isRight shouldBe true
      val userAMemories = userAResults.toOption.get
      userAMemories should not be empty
      userAMemories.foreach { m =>
        m.getMetadata("user_id") shouldBe Some("user-a")
        m.content should not include "Python"
      }

      vectorStore.close()
    }

  it should "not return user-A facts when querying for user-B context" in {
    val vectorStore = freshInMemoryStore(mockEmbeddingService)
    val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

    val m1 = manager.recordUserFact("user-A works at ACME Corp", Some("user-a"), Some(0.9)).fold(
      e => fail(s"user-A recordUserFact failed: ${e.message}"),
      identity
    )
    val m2 = m1.recordUserFact("user-B works at Globex Corp", Some("user-b"), Some(0.9)).fold(
      e => fail(s"user-B recordUserFact failed: ${e.message}"),
      identity
    )

    val userBFilter  = MemoryFilter.ByType(MemoryType.UserFact).and(MemoryFilter.ByMetadata("user_id", "user-b"))
    val userBResults = m2.store.recall(userBFilter)

    userBResults.isRight shouldBe true
    val userBMemories = userBResults.toOption.get
    userBMemories should not be empty
    userBMemories.foreach { m =>
      m.getMetadata("user_id") shouldBe Some("user-b")
      m.content should not include "ACME"
    }

    vectorStore.close()
  }

  it should "isolate facts for different users via getUserContext" in {
    val vectorStore = freshInMemoryStore(mockEmbeddingService)
    val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

    val m1 = manager.recordUserFact("Loves hiking in the mountains", Some("alice"), Some(0.7)).fold(
      e => fail(s"alice recordUserFact failed: ${e.message}"),
      identity
    )
    val m2 = m1.recordUserFact("Loves surfing on the ocean", Some("bob"), Some(0.7)).fold(
      e => fail(s"bob recordUserFact failed: ${e.message}"),
      identity
    )

    val aliceContext = m2.getUserContext(Some("alice"))
    aliceContext.isRight shouldBe true
    val aliceCtx = aliceContext.toOption.get
    aliceCtx should include("hiking")
    aliceCtx should not include "surfing"

    val bobContext = m2.getUserContext(Some("bob"))
    bobContext.isRight shouldBe true
    val bobCtx = bobContext.toOption.get
    bobCtx should include("surfing")
    bobCtx should not include "hiking"

    vectorStore.close()
  }

  // -----------------------------------------------------------------------
  // Test group 4: Memory expiry / recency scoring
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (recency)" should
    "return newer memories before older memories when recency-ordered" in {
      val vectorStore = freshInMemoryStore(mockEmbeddingService)

      val oldTime    = Instant.now().minusSeconds(3600)
      val recentTime = Instant.now().minusSeconds(60)

      val olderMemory = Memory(
        id = MemoryId.generate(),
        content = "older memory: user worked with Hadoop one year ago",
        memoryType = MemoryType.UserFact,
        metadata = Map("user_id" -> "recency-user"),
        timestamp = oldTime,
        importance = Some(0.5)
      )
      val recentMemory = Memory(
        id = MemoryId.generate(),
        content = "recent memory: user is now working with Spark",
        memoryType = MemoryType.UserFact,
        metadata = Map("user_id" -> "recency-user"),
        timestamp = recentTime,
        importance = Some(0.5)
      )

      vectorStore.store(olderMemory).fold(e => fail(s"store olderMemory failed: ${e.message}"), _ => ())
      vectorStore.store(recentMemory).fold(e => fail(s"store recentMemory failed: ${e.message}"), _ => ())

      val recallResult = vectorStore.recent(10, MemoryFilter.All)
      recallResult.isRight shouldBe true

      val memories = recallResult.toOption.get
      memories should have size 2

      // most recent first (recall is sorted by timestamp DESC)
      memories.head.timestamp shouldBe recentTime
      memories.last.timestamp shouldBe oldTime

      vectorStore.close()
    }

  it should "rank a recent high-importance memory above an old low-importance memory in importance filter" in {
    val vectorStore = freshInMemoryStore(mockEmbeddingService)

    val oldLowImportance = Memory(
      id = MemoryId.generate(),
      content = "old minor preference: user once liked vi editor",
      memoryType = MemoryType.UserFact,
      timestamp = Instant.now().minusSeconds(86400),
      importance = Some(0.1)
    )
    val recentHighImportance = Memory(
      id = MemoryId.generate(),
      content = "critical fact: user is the system administrator",
      memoryType = MemoryType.UserFact,
      timestamp = Instant.now().minusSeconds(30),
      importance = Some(0.95)
    )

    vectorStore.store(oldLowImportance).fold(e => fail(s"store failed: ${e.message}"), _ => ())
    vectorStore.store(recentHighImportance).fold(e => fail(s"store failed: ${e.message}"), _ => ())

    val highImportanceResults = vectorStore.recall(MemoryFilter.MinImportance(0.5))
    highImportanceResults.isRight shouldBe true

    val results = highImportanceResults.toOption.get
    results should have size 1
    results.head.content should include("system administrator")

    vectorStore.close()
  }

  // -----------------------------------------------------------------------
  // Test group 5: Cross-agent-instance persistence
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (cross-agent persistence)" should
    "allow agent-2 to retrieve memories recorded by agent-1 via shared file store" in {
      val dbFile = File.createTempFile(s"llm4s-cross-agent-${UUID.randomUUID()}", ".db")
      tempDbFile = Some(dbFile)

      // Agent 1 — records a distinctive fact
      val store1    = VectorMemoryStore(dbFile.getAbsolutePath, mockEmbeddingService).fold(
        e => fail(s"Failed to create store for agent-1: ${e.message}"),
        identity
      )
      val manager1  = SimpleMemoryManager.withStore(store1, MemoryManagerConfig.testing)
      val factText  = "Agent-1 recorded: user is an expert in distributed databases"

      manager1.recordUserFact(factText, Some("cross-agent-user"), Some(0.9)).fold(
        e => fail(s"agent-1 recordUserFact failed: ${e.message}"),
        _ => ()
      )
      store1.close()

      // Agent 2 — opens the same SQLite file and retrieves the memory recorded by agent-1
      val store2   = VectorMemoryStore(dbFile.getAbsolutePath, mockEmbeddingService).fold(
        e => fail(s"Failed to create store for agent-2: ${e.message}"),
        identity
      )
      val manager2 = SimpleMemoryManager.withStore(store2, MemoryManagerConfig.testing)

      val allMemories = manager2.store.recall(MemoryFilter.All)
      allMemories.isRight shouldBe true

      val memories = allMemories.toOption.get
      memories should not be empty
      memories.map(_.content) should contain(factText)

      store2.close()
    }

  it should "preserve memory count across two separate store instances on the same file" in {
    val dbFile = File.createTempFile(s"llm4s-persist-count-${UUID.randomUUID()}", ".db")
    tempDbFile = Some(dbFile)

    val store1   = VectorMemoryStore(dbFile.getAbsolutePath, mockEmbeddingService).fold(
      e => fail(s"Failed to create store: ${e.message}"),
      identity
    )
    val manager1 = SimpleMemoryManager.withStore(store1, MemoryManagerConfig.testing)

    val factsToWrite = (1 to 3).map(i => s"Persisted fact number $i")
    factsToWrite.foreach { fact =>
      manager1.recordUserFact(fact, Some("persist-user"), Some(0.7)).fold(
        e => fail(s"recordUserFact failed: ${e.message}"),
        _ => ()
      )
    }
    store1.close()

    val store2 = VectorMemoryStore(dbFile.getAbsolutePath, mockEmbeddingService).fold(
      e => fail(s"Failed to reopen store: ${e.message}"),
      identity
    )

    val countResult = store2.count(MemoryFilter.All)
    countResult.isRight shouldBe true
    countResult.toOption.get shouldBe 3L

    store2.close()
  }

  // -----------------------------------------------------------------------
  // Test group 6: Semantic search (requires Ollama for real embedding)
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (semantic search with MockEmbeddingService)" should
    "rank semantically similar content above unrelated content using mock embeddings" in {
      // Note: MockEmbeddingService uses deterministic hash-based embeddings.
      // For the same string, repeated embeds return the same vector so similarity == 1.0
      // (i.e., identical string always matches itself perfectly).

      val vectorStore = freshInMemoryStore(mockEmbeddingService)
      val manager     = SimpleMemoryManager.withStore(vectorStore, MemoryManagerConfig.testing)

      val relevantFact   = "user is an expert in machine learning and neural networks"
      val irrelevantFact = "user enjoys cooking Italian pasta on weekends"

      val m1 = manager.recordKnowledge(relevantFact, "profile").fold(
        e => fail(s"recordKnowledge failed: ${e.message}"),
        identity
      )
      val m2 = m1.recordKnowledge(irrelevantFact, "profile").fold(
        e => fail(s"recordKnowledge 2 failed: ${e.message}"),
        identity
      )

      // Search for a query closely related to the relevant fact
      val searchResult = m2.store.search("machine learning expertise", topK = 5)
      searchResult.isRight shouldBe true

      val results = searchResult.toOption.get
      results should not be empty

      // The relevant fact should appear in results; it need not be the absolute top
      // since MockEmbeddingService uses hash-based (not semantic) embeddings.
      val resultContents = results.map(_.memory.content)
      resultContents should contain(relevantFact)

      vectorStore.close()
    }

  it should "return empty search results when store is empty" in {
    val vectorStore = freshInMemoryStore(mockEmbeddingService)

    val searchResult = vectorStore.search("anything", topK = 10)
    searchResult.isRight shouldBe true
    searchResult.toOption.get shouldBe empty

    vectorStore.close()
  }

  // -----------------------------------------------------------------------
  // Test group 7: pgvector-backed tests (skip when PGVECTOR_TEST_URL not set)
  // -----------------------------------------------------------------------

  "VectorMemoryAgentIntegrationSpec (pgvector-backed)" should
    "store and retrieve user facts via PostgresMemoryStore when pgvector is available" in {
      assume(pgvectorAvailable, "PGVECTOR_TEST_URL not set — skipping pgvector integration test")

      val tableName = s"it_vma_facts_${System.currentTimeMillis()}"
      val pgConfig = PostgresMemoryStore.Config(
        host = "localhost",
        port = 5432,
        database = "postgres",
        user = "postgres",
        password = "password",
        tableName = tableName,
        maxPoolSize = 2
      )

      val pgStore = PostgresMemoryStore(pgConfig, Some(mockEmbeddingService)).fold(
        e => fail(s"Failed to create PostgresMemoryStore: ${e.message}"),
        identity
      )

      val manager = SimpleMemoryManager.withStore(pgStore, MemoryManagerConfig.testing)

      val fact    = "pg-test: user knows SQL very well"
      val updated = manager.recordUserFact(fact, Some("pg-user"), Some(0.9)).fold(
        e => fail(s"recordUserFact failed: ${e.message}"),
        identity
      )

      val allMemories = updated.store.recall(MemoryFilter.All)
      allMemories.isRight shouldBe true
      allMemories.toOption.get.map(_.content) should contain(fact)

      // Cleanup
      Try(pgStore.clear())
      pgStore.close()
    }

  it should "isolate user-A and user-B facts in PostgresMemoryStore" in {
    assume(pgvectorAvailable, "PGVECTOR_TEST_URL not set — skipping pgvector isolation test")

    val tableName = s"it_vma_isolation_${System.currentTimeMillis()}"
    val pgConfig = PostgresMemoryStore.Config(
      host = "localhost",
      port = 5432,
      database = "postgres",
      user = "postgres",
      password = "password",
      tableName = tableName,
      maxPoolSize = 2
    )

    val pgStore = PostgresMemoryStore(pgConfig, Some(mockEmbeddingService)).fold(
      e => fail(s"Failed to create PostgresMemoryStore: ${e.message}"),
      identity
    )

    val manager = SimpleMemoryManager.withStore(pgStore, MemoryManagerConfig.testing)

    val m1 = manager.recordUserFact("pg-user-A loves Scala", Some("pg-user-a"), Some(0.9)).fold(
      e => fail(s"user-A recordUserFact failed: ${e.message}"),
      identity
    )
    val m2 = m1.recordUserFact("pg-user-B loves Python", Some("pg-user-b"), Some(0.9)).fold(
      e => fail(s"user-B recordUserFact failed: ${e.message}"),
      identity
    )

    val filterA  = MemoryFilter.ByType(MemoryType.UserFact).and(MemoryFilter.ByMetadata("user_id", "pg-user-a"))
    val resultA  = m2.store.recall(filterA)
    resultA.isRight shouldBe true
    val memoriesA = resultA.toOption.get
    memoriesA should not be empty
    memoriesA.foreach { m =>
      m.getMetadata("user_id") shouldBe Some("pg-user-a")
      m.content should not include "Python"
    }

    val filterB  = MemoryFilter.ByType(MemoryType.UserFact).and(MemoryFilter.ByMetadata("user_id", "pg-user-b"))
    val resultB  = m2.store.recall(filterB)
    resultB.isRight shouldBe true
    val memoriesB = resultB.toOption.get
    memoriesB should not be empty
    memoriesB.foreach { m =>
      m.getMetadata("user_id") shouldBe Some("pg-user-b")
      m.content should not include "Scala"
    }

    // Cleanup
    Try(pgStore.clear())
    pgStore.close()
  }
}
