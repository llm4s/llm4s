package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import java.util.UUID
import scala.util.Try
import java.time.Instant
import java.sql.DriverManager

/**
 * Integration Tests for PostgresMemoryStore.
 * ENV VAR TOGGLE PATTERN:
 * These tests are skipped by default in CI to avoid dependency issues.
 * To run them locally:
 * 1. Start Postgres: docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16
 * 2. Enable Tests: export POSTGRES_TEST_ENABLED=true
 */
class PostgresMemoryStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  // 1. Env Var Check
  private val isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private var store: PostgresMemoryStore = _
  private val tableName                  = s"test_memories_${System.currentTimeMillis()}"

  // 2. Config: Use Env Vars or Defaults (Localhost)
  private val dbConfig = PostgresMemoryStore.Config(
    host = sys.env.getOrElse("POSTGRES_HOST", "localhost"),
    port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt,
    database = sys.env.getOrElse("POSTGRES_DB", "postgres"),
    user = sys.env.getOrElse("POSTGRES_USER", "postgres"),
    password = sys.env.getOrElse("POSTGRES_PASSWORD", "password"),
    tableName = tableName,
    maxPoolSize = 4
  )
  private val embeddingService = MockEmbeddingService.default

  override def beforeEach(): Unit =
    if (isEnabled) {
      store = PostgresMemoryStore(dbConfig, Some(embeddingService)).fold(e => fail(e.message), identity)
    }

  override def afterEach(): Unit =
    if (store != null) { Try(store.clear()); store.close() }

  // 3. Helper to skip tests
  private def skipIfDisabled(testBody: => Unit): Unit =
    if (isEnabled) testBody
    else info("Skipping Postgres test (POSTGRES_TEST_ENABLED=true not set)")

  it should "store and retrieve a conversation memory" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    val memory = Memory(
      id = id,
      content = "Hello, I am a test memory",
      memoryType = MemoryType.Conversation,
      metadata = Map("conversation_id" -> "conv-1")
    )

    store.store(memory).isRight shouldBe true

    store
      .get(id)
      .fold(
        e => fail(s"Get failed: ${e.message}"),
        {
          case Some(retrieved) =>
            retrieved.content shouldBe "Hello, I am a test memory"
            retrieved.metadata.get("conversation_id") shouldBe Some("conv-1")
          case None =>
            fail("Expected memory to be present, but got None")
        }
      )
  }

  it should "persist data across store instances" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "Persistence Check", MemoryType.Task)).isRight shouldBe true

    store.close()

    // Create a NEW connection (store2) to verify data is actually in the DB
    val store2 = PostgresMemoryStore(dbConfig).fold(e => fail(e.message), identity)

    store2
      .get(id)
      .fold(
        e => fail(s"Get failed on store2: ${e.message}"),
        {
          case Some(retrieved) =>
            retrieved.content shouldBe "Persistence Check"
          case None =>
            fail("Persistence check failed: Memory not found in new store instance")
        }
      )
    store2.close()
  }

  it should "perform semantic search" in skipIfDisabled {
    val applesEmbedding = embeddingService
      .embed("apples")
      .fold(
        e => fail(s"Test setup embedding failed: ${e.message}"),
        identity
      )
    val relevant = Memory(
      MemoryId("1"),
      "I like apples",
      MemoryType.Task,
      embedding = Some(applesEmbedding)
    )
    store.store(relevant)

    store
      .search("apple", 1, MemoryFilter.All)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        results =>
          results match {
            case first +: _ =>
              first.memory.content shouldBe "I like apples"
            case Nil =>
              fail("Expected at least one search result")
          }
      )
  }

  it should "fallback gracefully when EmbeddingService is missing" in skipIfDisabled {
    val storeNoEmb = PostgresMemoryStore(dbConfig, None).fold(e => fail(e.message), identity)
    val id         = MemoryId("fallback-1")
    storeNoEmb.store(Memory(id, "test fallback", MemoryType.Task, Map.empty))

    storeNoEmb
      .search("query", 5, MemoryFilter.All)
      .fold(
        e => fail(s"Fallback search failed: ${e.message}"),
        memories =>
          memories match {
            case first +: _ =>
              first.score shouldBe 0.0
            case Nil =>
              fail("Expected fallback search to return at least one memory")
          }
      )
    storeNoEmb.close()
  }

  it should "clamp similarity scores to [0, 1]" in skipIfDisabled {
    val clampEmbedding = embeddingService
      .embed("clamp example")
      .fold(
        e => fail(s"Test setup embedding failed: ${e.message}"),
        identity
      )

    val memory = Memory(
      MemoryId("clamp-test"),
      "clamp example",
      MemoryType.Task,
      embedding = Some(clampEmbedding)
    )
    store.store(memory).isRight shouldBe true

    store
      .search("clamp", 5, MemoryFilter.All)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        results =>
          results match {
            case _ +: _ =>
              results.foreach { sm =>
                sm.score should be >= 0.0
                sm.score should be <= 1.0
              }
            case Nil =>
              fail("Expected non-empty results for clamp test")
          }
      )
  }

  it should "fail when embedding service returns empty vector" in skipIfDisabled {
    val emptyService = new EmbeddingService {
      def dimensions                                = 10
      def embed(text: String): Result[Array[Float]] = Right(Array.empty)
      def embedBatch(texts: Seq[String])            = Right(Seq.empty)
    }

    val storeWithEmpty = PostgresMemoryStore(dbConfig, Some(emptyService))
      .fold(e => fail(e.message), identity)

    storeWithEmpty
      .search("query", 5, MemoryFilter.All)
      .fold(
        err => err.message should include("vector is empty"),
        _ => fail("Expected search to fail due to empty embedding, but it succeeded")
      )
    storeWithEmpty.close()
  }

  it should "propagate embedding service failures" in skipIfDisabled {
    val failingService = new EmbeddingService {
      def dimensions                                = 10
      def embed(text: String): Result[Array[Float]] = Left(ProcessingError("embed-error", "boom"))
      def embedBatch(texts: Seq[String])            = Left(ProcessingError("embed-error", "boom"))
    }

    val storeFailing = PostgresMemoryStore(dbConfig, Some(failingService))
      .fold(e => fail(e.message), identity)

    storeFailing
      .search("query", 5, MemoryFilter.All)
      .fold(
        err => err.message should include("boom"),
        _ => fail("Expected search to fail due to embedding service error, but it succeeded")
      )
    storeFailing.close()
  }

  it should "reject invalid metadata key in filter" in skipIfDisabled {
    val badFilter = MemoryFilter.ByMetadata("invalid-key!", "value")

    store
      .search("query", 5, badFilter)
      .fold(
        err => err.message should include("Invalid metadata key"),
        _ => fail("Expected invalid metadata key filter to fail")
      )
  }

  it should "filter by valid metadata key and value" in skipIfDisabled {
    val mem = Memory(
      MemoryId("meta-test"),
      "metadata example",
      MemoryType.Task,
      metadata = Map("custom_key" -> "custom_value")
    )
    store.store(mem).isRight shouldBe true

    val filter = MemoryFilter.ByMetadata("custom_key", "custom_value")

    store
      .recall(filter, 10)
      .fold(
        e => fail(s"Metadata recall failed: ${e.message}"),
        results =>
          results match {
            case first +: _ => first.content shouldBe "metadata example"
            case Nil        => fail("Expected to find memory by metadata")
          }
      )
  }

  it should "filter by entity id" in skipIfDisabled {
    val mem = Memory(
      MemoryId("entity-test"),
      "entity example",
      MemoryType.Task,
      metadata = Map("entity_id" -> "user-123")
    )
    store.store(mem).isRight shouldBe true

    store
      .recall(MemoryFilter.ByEntity(EntityId("user-123")), 10)
      .fold(
        e => fail(s"Entity recall failed: ${e.message}"),
        results => results should not be empty
      )
  }

  it should "filter by conversation id" in skipIfDisabled {
    val mem = Memory(
      MemoryId("conv-test"),
      "conversation example",
      MemoryType.Conversation,
      metadata = Map("conversation_id" -> "conv-999")
    )
    store.store(mem).isRight shouldBe true

    store
      .recall(MemoryFilter.ByConversation("conv-999"), 10)
      .fold(
        e => fail(s"Conversation recall failed: ${e.message}"),
        results => results should not be empty
      )
  }

  it should "return empty result for MemoryFilter.None" in skipIfDisabled {
    store
      .search("anything", 5, MemoryFilter.None)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        results => results shouldBe empty
      )
  }

  it should "filter by single memory type" in skipIfDisabled {
    val mem = Memory(
      MemoryId("single-type"),
      "single type example",
      MemoryType.Task
    )
    store.store(mem).isRight shouldBe true

    store
      .recall(MemoryFilter.ByType(MemoryType.Task), 10)
      .fold(
        e => fail(s"Recall ByType failed: ${e.message}"),
        results => results should not be empty
      )
  }

  it should "filter by multiple memory types" in skipIfDisabled {
    val m1 = Memory(MemoryId("type-1"), "task", MemoryType.Task)
    val m2 = Memory(MemoryId("type-2"), "conversation", MemoryType.Conversation)

    store.store(m1).isRight shouldBe true
    store.store(m2).isRight shouldBe true

    val filter = MemoryFilter.ByTypes(Set(MemoryType.Task))

    store
      .recall(filter, 10)
      .fold(
        e => fail(s"Recall failed: ${e.message}"),
        results => {
          results should not be empty
          results.foreach(m => m.memoryType shouldBe MemoryType.Task)
        }
      )
  }

  it should "return empty for ByTypes with empty set" in skipIfDisabled {
    val filter = MemoryFilter.ByTypes(Set.empty)

    store
      .recall(filter, 10)
      .fold(
        e => fail(s"Recall failed: ${e.message}"),
        results => results shouldBe empty
      )
  }

  it should "filter by minimum importance" in skipIfDisabled {
    val mem = Memory(
      MemoryId("importance-test"),
      "important memory",
      MemoryType.Task,
      importance = Some(0.9)
    )
    store.store(mem).isRight shouldBe true

    store
      .recall(MemoryFilter.MinImportance(0.5), 10)
      .fold(
        e => fail(s"Recall failed: ${e.message}"),
        results => results should not be empty
      )
  }

  it should "support time range filtering" in skipIfDisabled {
    val now = Instant.now()
    val old = now.minusSeconds(3600)

    val mem = Memory(
      MemoryId("time-test"),
      "time example",
      MemoryType.Task,
      timestamp = now
    )
    store.store(mem).isRight shouldBe true

    val filter = MemoryFilter.ByTimeRange(Some(old), Some(now.plusSeconds(10)))

    store
      .recall(filter, 10)
      .fold(
        e => fail(s"Time range recall failed: ${e.message}"),
        results => results should not be empty
      )
  }

  it should "fail gracefully when stored embedding is corrupted" in skipIfDisabled {
    val conn = DriverManager.getConnection(
      dbConfig.jdbcUrl,
      dbConfig.user,
      dbConfig.password
    )
    try {
      val stmt = conn.createStatement()
      stmt.executeUpdate(
        s"INSERT INTO ${dbConfig.tableName} (id, content, memory_type, metadata, created_at, embedding) " +
          s"VALUES ('bad-vec-id', 'bad content', 'Task', '{}', NOW(), '[bad-vec]')"
      )
    } finally
      conn.close()

    store
      .get(MemoryId("bad-vec-id"))
      .fold(
        err => err.message should include("vector embedding"),
        _ => fail("Expected vector parse failure due to corrupted DB data")
      )
  }
}
