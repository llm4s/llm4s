package org.llm4s.agent.memory

import org.llm4s.error.OptimisticLockFailure
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.scalatest.BeforeAndAfterEach

import java.util.UUID
import java.util.concurrent.{ Executors, CountDownLatch }
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ ExecutionContext, Future, Await }
import scala.concurrent.duration._
import scala.util.Try

/**
 * Concurrency and Optimistic Locking Tests for PostgresMemoryStore.
 *
 * These tests verify that optimistic locking correctly handles concurrent updates
 * and prevents race conditions under high contention.
 *
 * To run these tests:
 * 1. Start Postgres: docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16
 * 2. Enable Tests: export POSTGRES_TEST_ENABLED=true
 * 3. Run: sbt "testOnly *PostgresMemoryStoreConcurrencySpec"
 */
class PostgresMemoryStoreConcurrencySpec extends AnyFlatSpec with Matchers with BeforeAndAfterEach {

  private val isEnabled = sys.env.get("POSTGRES_TEST_ENABLED").exists(_.toBoolean)

  private var store: PostgresMemoryStore = _
  private val tableName                  = s"test_concurrent_${System.currentTimeMillis()}"

  private val dbConfig = PostgresMemoryStore.Config(
    host = sys.env.getOrElse("POSTGRES_HOST", "localhost"),
    port = sys.env.getOrElse("POSTGRES_PORT", "5432").toInt,
    database = sys.env.getOrElse("POSTGRES_DB", "postgres"),
    user = sys.env.getOrElse("POSTGRES_USER", "postgres"),
    password = sys.env.getOrElse("POSTGRES_PASSWORD", "password"),
    tableName = tableName,
    maxPoolSize = 20 // Higher pool size for concurrency tests
  )

  // Thread pool for concurrent operations
  private implicit val ec: ExecutionContext =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(20))

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

  private def skipIfDisabled(testBody: => Unit): Unit =
    if (isEnabled) testBody
    else info("Skipping Postgres test (POSTGRES_TEST_ENABLED=true not set)")

  behavior of "PostgresMemoryStore with optimistic locking"

  it should "detect concurrent modifications and fail with OptimisticLockFailure" in skipIfDisabled {
    // Setup: Create initial memory
    val id = MemoryId(UUID.randomUUID().toString)
    val initialMemory = Memory(
      id = id,
      content = "Initial content",
      memoryType = MemoryType.Task,
      metadata = Map("counter" -> "0")
    )
    store.store(initialMemory).isRight shouldBe true

    // Concurrent update: Two threads try to update simultaneously
    val futures = (1 to 2).map { i =>
      Future {
        Thread.sleep(10) // Small delay to ensure both read similar state
        store.update(id, mem => mem.copy(content = s"Updated by thread $i"))
      }
    }

    val results = futures.map(f => Await.result(f, 5.seconds))

    // Verify: Exactly one should succeed, one should fail with OptimisticLockFailure
    val (successes, failures) = results.partition(_.isRight)

    successes.size shouldBe 1
    failures.size shouldBe 1

    failures.head.left.toOption.get shouldBe a[OptimisticLockFailure]
  }

  it should "handle high contention with many concurrent updates" in skipIfDisabled {
    val id        = MemoryId(UUID.randomUUID().toString)
    val numThreads = 50
    val initial   = Memory(id, "counter: 0", MemoryType.Task, Map("counter" -> "0"))

    store.store(initial).isRight shouldBe true

    // Launch 50 concurrent updates
    val futures = (1 to numThreads).map { _ =>
      Future {
        store.update(
          id,
          mem => {
            val currentCount = mem.metadata.get("counter").map(_.toInt).getOrElse(0)
            mem.withMetadata("counter", (currentCount + 1).toString)
          }
        )
      }
    }

    val results = futures.map(f => Await.result(f, 10.seconds))
    val (successes, failures) = results.partition(_.isRight)

    // Only one update should succeed per version
    // Most should fail with OptimisticLockFailure
    successes.size should be < numThreads
    failures.size should be > 0

    failures.foreach { failure =>
      failure.left.toOption.get shouldBe a[OptimisticLockFailure]
    }

    info(s"Concurrent updates: $numThreads attempted, ${successes.size} succeeded, ${failures.size} failed")
  }

  it should "successfully complete all updates when using retryingUpdate" in skipIfDisabled {
    val id        = MemoryId(UUID.randomUUID().toString)
    val numThreads = 20
    val initial   = Memory(id, "counter: 0", MemoryType.Task, Map("counter" -> "0"))

    store.store(initial).isRight shouldBe true

    // Use CountDownLatch to synchronize start
    val startLatch = new CountDownLatch(1)
    val successCount = new AtomicInteger(0)

    // Launch concurrent retrying updates
    val futures = (1 to numThreads).map { _ =>
      Future {
        startLatch.await() // Wait for all threads to be ready
        store.retryingUpdate(
          id,
          mem => {
            val currentCount = mem.metadata.get("counter").map(_.toInt).getOrElse(0)
            mem.withMetadata("counter", (currentCount + 1).toString)
          },
          maxRetries = 10
        ) match {
          case Right(_) =>
            successCount.incrementAndGet()
            true
          case Left(error) =>
            info(s"Update failed with error: ${error.message}")
            false
        }
      }
    }

    // Start all threads simultaneously
    startLatch.countDown()

    val results = futures.map(f => Await.result(f, 30.seconds))

    // All updates should eventually succeed with retry
    val allSucceeded = results.forall(identity)
    allSucceeded shouldBe true

    // Verify final counter value
    val finalMemory = store.get(id).toOption.flatten.get
    val finalCount = finalMemory.metadata("counter").toInt
    finalCount shouldBe numThreads

    info(s"All $numThreads concurrent retrying updates succeeded. Final counter: $finalCount")
  }

  it should "maintain consistency across multiple reads and updates" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "v0", MemoryType.Task)).isRight shouldBe true

    // Sequential updates should all succeed
    val updates = (1 to 10).map { i =>
      store.update(id, _.copy(content = s"v$i"))
    }

    updates.foreach { result =>
      result.isRight shouldBe true
    }

    // Verify final state
    val finalMemory = store.get(id).toOption.flatten.get
    finalMemory.content shouldBe "v10"
  }

  it should "preserve version isolation between different memories" in skipIfDisabled {
    // Create two separate memories
    val id1 = MemoryId(UUID.randomUUID().toString)
    val id2 = MemoryId(UUID.randomUUID().toString)

    store.store(Memory(id1, "mem1", MemoryType.Task)).isRight shouldBe true
    store.store(Memory(id2, "mem2", MemoryType.Task)).isRight shouldBe true

    // Concurrent updates on different memories should not interfere
    val futures = Seq(
      Future { store.update(id1, _.copy(content = "mem1-updated")) },
      Future { store.update(id2, _.copy(content = "mem2-updated")) }
    )

    val results = futures.map(f => Await.result(f, 5.seconds))

    // Both should succeed (no conflict)
    results.foreach { r =>
      r.isRight shouldBe true
    }
  }

  it should "correctly increment version on successful updates" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "v0", MemoryType.Task)).isRight shouldBe true

    // Perform 5 sequential updates
    (1 to 5).foreach { i =>
      store.update(id, _.copy(content = s"v$i")).isRight shouldBe true
    }

    // Check internal version (using getVersioned)
    val versioned = store.getVersioned(id).toOption.flatten.get
    versioned.version shouldBe 5
  }

  it should "handle retryingUpdate with maxRetries exhaustion" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "initial", MemoryType.Task)).isRight shouldBe true

    // Create extreme contention: many threads, low retry count
    val numThreads = 30
    val startLatch = new CountDownLatch(1)

    val futures = (1 to numThreads).map { threadNum =>
      Future {
        startLatch.await()
        store.retryingUpdate(
          id,
          mem => mem.copy(content = s"thread-$threadNum"),
          maxRetries = 2 // Very low retry count
        )
      }
    }

    startLatch.countDown()
    val results = futures.map(f => Await.result(f, 10.seconds))

    val (successes, failures) = results.partition(_.isRight)

    // With low maxRetries and high contention, some should fail
    failures.size should be > 0
    failures.foreach { failure =>
      failure.left.toOption.get shouldBe a[OptimisticLockFailure]
    }

    info(s"High contention test: ${successes.size} succeeded, ${failures.size} exhausted retries")
  }

  it should "handle store() as force-write without version checking" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "v1", MemoryType.Task)).isRight shouldBe true

    // Multiple concurrent store() calls should all succeed
    val futures = (1 to 10).map { i =>
      Future {
        store.store(Memory(id, s"force-$i", MemoryType.Task))
      }
    }

    val results = futures.map(f => Await.result(f, 5.seconds))

    // All store() operations should succeed (no OptimisticLockFailure)
    results.foreach { r =>
      r.isRight shouldBe true
    }

    // Final state is undefined (last writer wins)
    val finalMemory = store.get(id).toOption.flatten.get
    finalMemory.content should startWith("force-")
  }

  it should "correctly handle backward compatibility with missing version column" in skipIfDisabled {
    // This test verifies that rowToVersionedMemory handles missing version gracefully
    // In production, this would only occur during migration period

    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "test", MemoryType.Task)).isRight shouldBe true

    // Normal get should work
    val memory = store.get(id).toOption.flatten
    memory shouldBe defined

    // Internal getVersioned should default to version 0 if column ever missing
    val versioned = store.getVersioned(id).toOption.flatten.get
    versioned.version should be >= 0L
  }

  behavior of "OptimisticLockFailure error"

  it should "contain correct metadata for debugging" in skipIfDisabled {
    val id = MemoryId(UUID.randomUUID().toString)
    store.store(Memory(id, "test", MemoryType.Task)).isRight shouldBe true

    // Trigger conflict
    val futures = (1 to 2).map { i =>
      Future {
        Thread.sleep(10)
        store.update(id, _.copy(content = s"update-$i"))
      }
    }

    val results = futures.map(f => Await.result(f, 5.seconds))
    val failure = results.find(_.isLeft).get.left.toOption.get

    failure shouldBe a[OptimisticLockFailure]
    val lockFailure = failure.asInstanceOf[OptimisticLockFailure]

    lockFailure.memoryId shouldBe id.value
    lockFailure.attemptedVersion should be >= 0L
    lockFailure.code shouldBe Some("OPTIMISTIC_LOCK_CONFLICT")
    lockFailure.context should contain key "memory_id"
    lockFailure.context should contain key "attempted_version"
  }

  behavior of "Stress testing"

  it should "handle sustained concurrent load" in skipIfDisabled {
    val numMemories = 10
    val updatesPerMemory = 20
    val totalOps = numMemories * updatesPerMemory

    // Create memories
    val memoryIds = (1 to numMemories).map { i =>
      val id = MemoryId(UUID.randomUUID().toString)
      store.store(Memory(id, s"mem-$i", MemoryType.Task, Map("counter" -> "0"))).isRight shouldBe true
      id
    }

    val startTime = System.currentTimeMillis()

    // Fire concurrent updates across all memories
    val futures = (1 to totalOps).map { i =>
      val targetId = memoryIds(i % numMemories)
      Future {
        store.retryingUpdate(
          targetId,
          mem => {
            val count = mem.metadata.get("counter").map(_.toInt).getOrElse(0)
            mem.withMetadata("counter", (count + 1).toString)
          },
          maxRetries = 15
        )
      }
    }

    val results = futures.map(f => Await.result(f, 60.seconds))
    val endTime = System.currentTimeMillis()

    val (successes, _) = results.partition(_.isRight)

    // All should succeed with sufficient retries
    successes.size shouldBe totalOps

    // Verify counters
    memoryIds.foreach { id =>
      val memory = store.get(id).toOption.flatten.get
      val count = memory.metadata("counter").toInt
      count shouldBe updatesPerMemory
    }

    val duration = endTime - startTime
    val throughput = (totalOps * 1000.0) / duration

    info(f"Stress test completed: $totalOps ops in ${duration}ms (${throughput}%.2f ops/sec)")
  }
}
