package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import java.util.UUID
import scala.util.Try

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
    tableName = tableName
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
    val id     = MemoryId(UUID.randomUUID().toString)
    val memory = Memory(id, "test", MemoryType.Conversation, Map.empty)
    store.store(memory).isRight shouldBe true
    val retrieved = store.get(id).toOption.flatten
    retrieved shouldBe defined
  }

  it should "persist data across store instances" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "Persistence Check", MemoryType.Task)).isRight shouldBe true

    store.close()

    // Create a NEW connection (store2) to verify data is actually in the DB
    val store2 = PostgresMemoryStore(dbConfig).fold(e => fail(e.message), identity)

    val result = store2.get(id)
    result.toOption.flatten.map(_.content) shouldBe Some("Persistence Check")
    store2.close()
  }

  it should "perform semantic search" in skipIfDisabled {
    val embedding = embeddingService.embed("apples").fold(e => fail(e.message), identity)
    val relevant = Memory(
      MemoryId("1"),
      "I like apples",
      MemoryType.Task,
      embedding = Some(embedding)
    )
    store.store(relevant)
    store
      .search("apple", 1, MemoryFilter.All)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        results => results.head.memory.content shouldBe "I like apples"
      )
  }

  it should "fallback gracefully when EmbeddingService is missing" in skipIfDisabled {
    val storeNoEmb = PostgresMemoryStore(dbConfig, None).fold(e => fail(e.message), identity)
    val id         = MemoryId("fallback-1")
    storeNoEmb.store(Memory(id, "test fallback", MemoryType.Task, Map.empty))

    storeNoEmb
      .search("query", 5, MemoryFilter.All)
      .fold(
        e => fail(s"Search failed: ${e.message}"),
        memories => {
          memories.nonEmpty shouldBe true
          memories.head.score shouldBe 0.0
        }
      )
    storeNoEmb.close()
  }

  it should "clamp similarity scores to [0, 1]" in skipIfDisabled {
    store.search("anything", 5, MemoryFilter.All).foreach { results =>
      results.foreach { sm =>
        sm.score should be >= 0.0
        sm.score should be <= 1.0
      }
    }
  }

  it should "fail when embedding service returns empty vector" in skipIfDisabled {
    val emptyService = new EmbeddingService {
      def dimensions                                = 10
      def embed(text: String): Result[Array[Float]] = Right(Array.empty)
      def embedBatch(texts: Seq[String])            = Right(Seq.empty)
    }

    val storeWithEmpty = PostgresMemoryStore(dbConfig, Some(emptyService))
      .fold(e => fail(e.message), identity)

    val result = storeWithEmpty.search("query", 5, MemoryFilter.All)
    result shouldBe a[Left[_, _]]
    result.left.foreach(_.message should include("vector is empty"))
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

    val result = storeFailing.search("query", 5, MemoryFilter.All)
    result shouldBe a[Left[_, _]]
    result.left.foreach(_.message should include("boom"))
    storeFailing.close()
  }
}
