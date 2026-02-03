package org.llm4s.agent.memory

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach
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
    tableName = tableName,
    maxPoolSize = 4
  )

  override def beforeEach(): Unit =
    if (isEnabled) {
      store = PostgresMemoryStore(dbConfig).fold(
        e => fail(s"Failed to connect to Postgres: ${e.message}"),
        identity
      )
    }

  override def afterEach(): Unit =
    if (store != null) {
      Try(store.clear())
      store.close()
    }

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

    val retrieved = store.get(id).toOption.flatten
    retrieved shouldBe defined
    retrieved.get.content shouldBe "Hello, I am a test memory"
    retrieved.get.metadata.get("conversation_id") shouldBe Some("conv-1")
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
}
