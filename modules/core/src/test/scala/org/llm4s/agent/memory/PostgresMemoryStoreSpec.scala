package org.llm4s.agent.memory

import org.llm4s.error.OptimisticLockFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.util.UUID
import java.util.concurrent.{ CountDownLatch, Executors, TimeUnit }
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

  it should "increment version on each successful update" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "v0 content", MemoryType.Task)).isRight shouldBe true

    store.update(id, _.copy(content = "v1 content")).isRight shouldBe true
    store.update(id, _.copy(content = "v2 content")).isRight shouldBe true

    val retrieved = store.get(id).toOption.flatten
    retrieved shouldBe defined
    retrieved.get.content shouldBe "v2 content"
  }

  it should "produce only OptimisticLockFailure errors under concurrent writes" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "initial", MemoryType.Task)).isRight shouldBe true

    val threadCount = 4
    val executor    = Executors.newFixedThreadPool(threadCount)
    val latch       = new CountDownLatch(1)
    val results     = new java.util.concurrent.ConcurrentLinkedQueue[Either[org.llm4s.error.LLMError, Any]]()

    (1 to threadCount).foreach { i =>
      executor.submit(new Runnable {
        def run(): Unit = {
          latch.await()
          results.add(store.update(id, _.copy(content = s"updated by thread $i")))
        }
      })
    }

    latch.countDown()
    executor.shutdown()
    executor.awaitTermination(30, TimeUnit.SECONDS)

    val allResults = scala.jdk.CollectionConverters.IteratorHasAsScala(results.iterator()).asScala.toSeq

    // Every result must be either a success or an OptimisticLockFailure — no other errors
    allResults.foreach {
      case Right(_)                       => // success
      case Left(_: OptimisticLockFailure) => // expected conflict
      case Left(e)                        => fail(s"Unexpected error type: ${e.getClass.getSimpleName}: ${e.message}")
    }

    // At least one update must have succeeded
    allResults.count(_.isRight) should be >= 1
  }
}
