package org.llm4s.agent.memory

import org.llm4s.error.ProcessingError
import org.llm4s.types.Result
import org.scalatest.BeforeAndAfterEach
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.UUID
import scala.util.Try

/**
 * Integration tests for PostgresMemoryStore.
 *
 * These tests are skipped by default in CI to avoid dependency issues.
 * To run them locally:
 *   1. Start Postgres: docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16
 *   2. Enable tests: export POSTGRES_TEST_ENABLED=true
 *   3. Run: sbt "it/testOnly org.llm4s.agent.memory.PostgresMemoryStoreSpec"
 */
class PostgresMemoryStoreSpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private var store: PostgresMemoryStore = _
  private val tableName                  = s"test_memories_${System.currentTimeMillis()}"

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

    store.get(id).fold(
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
    val store2 = PostgresMemoryStore(dbConfig).fold(e => fail(e.message), identity)

    store2.get(id).fold(
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
    val applesEmbedding = embeddingService.embed("apples").fold(
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

    store.search("apple", 1, MemoryFilter.All).fold(
      e => fail(s"Search failed: ${e.message}"),
      results =>
        results match {
          case first +: _ => first.memory.content shouldBe "I like apples"
          case Nil        => fail("Expected at least one search result")
        }
    )
  }

  it should "fallback gracefully when EmbeddingService is missing" in skipIfDisabled {
    val storeNoEmb = PostgresMemoryStore(dbConfig, None).fold(e => fail(e.message), identity)
    val id         = MemoryId("fallback-1")
    storeNoEmb.store(Memory(id, "test fallback", MemoryType.Task, Map.empty))

    storeNoEmb.search("query", 5, MemoryFilter.All).fold(
      e => fail(s"Fallback search failed: ${e.message}"),
      memories =>
        memories match {
          case first +: _ => first.score shouldBe 0.0
          case Nil        => fail("Expected fallback search to return at least one memory")
        }
    )
    storeNoEmb.close()
  }

  it should "clamp similarity scores to [0, 1]" in skipIfDisabled {
    val clampEmbedding = embeddingService.embed("clamp example").fold(
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

    store.search("clamp", 5, MemoryFilter.All).fold(
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

    val storeWithEmpty = PostgresMemoryStore(dbConfig, Some(emptyService)).fold(e => fail(e.message), identity)

    storeWithEmpty.search("query", 5, MemoryFilter.All).fold(
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

    val storeFailing = PostgresMemoryStore(dbConfig, Some(failingService)).fold(e => fail(e.message), identity)

    storeFailing.search("query", 5, MemoryFilter.All).fold(
      err => err.message should include("boom"),
      _ => fail("Expected search to fail due to embedding service error, but it succeeded")
    )
    storeFailing.close()
  }
}
