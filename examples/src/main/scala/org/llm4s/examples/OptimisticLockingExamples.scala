package org.llm4s.examples

import org.llm4s.agent.memory._
import org.llm4s.error.OptimisticLockFailure

import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.Await

/**
 * Examples demonstrating optimistic locking in PostgresMemoryStore.
 *
 * These examples show how to safely handle concurrent updates using
 * the version-based optimistic locking mechanism.
 */
object OptimisticLockingExamples {

  /**
   * Example 1: Basic update with manual conflict handling
   *
   * Demonstrates bounded retry pattern to prevent infinite loops.
   */
  def basicUpdateWithRetry(store: PostgresMemoryStore, memoryId: MemoryId, maxAttempts: Int = 3): Unit = {
    def attemptUpdate(attemptsLeft: Int = maxAttempts): Unit = {
      store.update(memoryId, mem => mem.withMetadata("updated", "true")) match {
        case Right(_) =>
          println(s"✓ Update succeeded")

        case Left(OptimisticLockFailure(id, version)) if attemptsLeft > 0 =>
          println(s"⚠ Conflict detected on $id at version $version, retrying...")
          Thread.sleep(50) // Brief pause before retry
          attemptUpdate(attemptsLeft - 1)

        case Left(OptimisticLockFailure(id, version)) =>
          println(s"✗ Update failed after retries: $id at version $version")

        case Left(error) =>
          println(s"✗ Update failed: ${error.message}")
      }
    }

    attemptUpdate()
  }

  /**
   * Example 2: Using retryingUpdate for automatic conflict resolution
   */
  def automaticRetryUpdate(store: PostgresMemoryStore, memoryId: MemoryId): Unit = {
    store.retryingUpdate(
      memoryId,
      mem => mem.withMetadata("likes", 
        (mem.metadata.get("likes").map(_.toInt).getOrElse(0) + 1).toString
      ),
      maxRetries = 5
    ) match {
      case Right(_) =>
        println(s"✓ Update succeeded (possibly with retries)")

      case Left(error) =>
        println(s"✗ Update failed: ${error.message}")
    }
  }

  /**
   * Example 3: Concurrent counter increments
   *
   * This simulates multiple threads incrementing a counter concurrently.
   * Without optimistic locking, updates would be lost.
   */
  def concurrentCounterExample(store: PostgresMemoryStore): Unit = {
    // Create initial memory with counter at 0
    val memoryId = MemoryId.generate()
    store.store(
      Memory(
        id = memoryId,
        content = "Shared counter",
        memoryType = MemoryType.Task,
        metadata = Map("counter" -> "0")
      )
    )

    // Launch 10 concurrent increments
    val futures = (1 to 10).map { _ =>
      Future {
        store.retryingUpdate(
          memoryId,
          mem => {
            val current = mem.metadata.get("counter").map(_.toInt).getOrElse(0)
            mem.withMetadata("counter", (current + 1).toString)
          },
          maxRetries = 10
        )
      }
    }

    // Wait for all updates to complete
    val results = futures.map(f => Await.result(f, 10.seconds))

    // Verify final counter value
    val finalMemory = store.get(memoryId) match {
      case Right(Some(mem)) => mem
      case Right(None) => throw new RuntimeException("Memory not found")
      case Left(err) => throw new RuntimeException(s"Failed to get memory: ${err.message}")
    }
    val finalCount = finalMemory.metadata("counter").toInt

    println(s"✓ All 10 concurrent increments completed")
    println(s"  Final counter value: $finalCount (expected: 10)")

    if (finalCount == 10) {
      println(s"  ✓ No updates lost - optimistic locking working correctly!")
    } else {
      println(s"  ✗ Updates lost - expected 10, got $finalCount")
    }
  }

  /**
   * Example 4: Choosing between store() and update()
   */
  def storeVsUpdateExample(store: PostgresMemoryStore): Unit = {
    val memoryId = MemoryId.generate()

    // Creating new memory: Use store()
    println("Creating new memory with store()...")
    store.store(
      Memory(memoryId, "Initial content", MemoryType.Task)
    ) match {
      case Right(_) => println("  ✓ Memory created")
      case Left(e)  => println(s"  ✗ Failed: ${e.message}")
    }

    // Single-writer scenario: Can use store() for simplicity
    println("\nSingle-writer update with store()...")
    store.store(
      Memory(memoryId, "Updated content", MemoryType.Task)
    ) match {
      case Right(_) => println("  ✓ Memory updated (version incremented)")
      case Left(e)  => println(s"  ✗ Failed: ${e.message}")
    }

    // Multi-writer scenario: Use update() or retryingUpdate()
    println("\nMulti-writer update with retryingUpdate()...")
    store.retryingUpdate(
      memoryId,
      mem => mem.copy(content = mem.content + " [verified]")
    ) match {
      case Right(_) => println("  ✓ Safe concurrent update completed")
      case Left(e)  => println(s"  ✗ Failed: ${e.message}")
    }
  }

  /**
   * Example 5: Complex state transition with validation
   */
  def stateMachineExample(store: PostgresMemoryStore, memoryId: MemoryId): Unit = {
    // State machine: pending → processing → completed
    // Only valid transitions should succeed

    def transitionState(from: String, to: String): Unit = {
      println(s"\nAttempting transition: $from → $to")

      store.retryingUpdate(
        memoryId,
        mem => {
          val currentState = mem.metadata.getOrElse("state", "unknown")

          // Validate transition
          val isValidTransition = (currentState, to) match {
            case ("pending", "processing") => true
            case ("processing", "completed") => true
            case ("processing", "failed") => true
            case _ => false
          }

          if (isValidTransition) {
            println(s"  Valid transition from '$currentState'")
            mem.withMetadata("state", to)
          } else {
            println(s"  ✗ Invalid transition from '$currentState' to '$to'")
            mem // No change
          }
        },
        maxRetries = 3
      ) match {
        case Right(_) => println(s"  ✓ Transition completed")
        case Left(e)  => println(s"  ✗ Transition failed: ${e.message}")
      }
    }

    // Create initial state
    store.store(
      Memory(
        id = memoryId,
        content = "Task workflow",
        memoryType = MemoryType.Task,
        metadata = Map("state" -> "pending")
      )
    )

    // Valid transitions
    transitionState("pending", "processing")
    transitionState("processing", "completed")

    // Invalid transition (should be no-op)
    transitionState("completed", "pending")
  }

  /**
   * Example 6: Monitoring and logging conflicts
   */
  def monitoringExample(store: PostgresMemoryStore, memoryId: MemoryId): Unit = {
    var conflictCount = 0
    var successCount = 0

    def updateWithMonitoring(): Unit = {
      store.update(
        memoryId,
        mem => mem.withMetadata("timestamp", System.currentTimeMillis().toString)
      ) match {
        case Right(_) =>
          successCount += 1
          println(s"✓ Update succeeded (total: $successCount)")

        case Left(OptimisticLockFailure(id, version)) =>
          conflictCount += 1
          println(s"⚠ Conflict #$conflictCount on $id at version $version")

          // Log to monitoring system
          // metrics.increment("memory.optimistic_lock_conflict")
          // logger.warn(s"Optimistic lock conflict: $id @ v$version")

          // Retry
          Thread.sleep(10)
          updateWithMonitoring()

        case Left(error) =>
          println(s"✗ Error: ${error.message}")
      }
    }

    updateWithMonitoring()

    println(s"\nStatistics:")
    println(s"  Successes: $successCount")
    println(s"  Conflicts: $conflictCount")
    println(s"  Conflict rate: ${conflictCount.toDouble / (successCount + conflictCount) * 100}%")
  }

  /**
   * Main example runner
   */
  def main(args: Array[String]): Unit = {
    // Initialize store
    val config = PostgresMemoryStore.Config(
      host = "localhost",
      port = 5432,
      database = "postgres",
      user = "postgres",
      password = "password",
      tableName = "examples_memories"
    )

    PostgresMemoryStore(config) match {
      case Right(store) =>
        try {
          println("=" * 60)
          println("  Optimistic Locking Examples")
          println("=" * 60)

          // Run examples
          println("\n[Example 1: Basic Update with Retry]")
          val id1 = MemoryId.generate()
          store.store(Memory(id1, "test", MemoryType.Task))
          basicUpdateWithRetry(store, id1)

          println("\n[Example 2: Automatic Retry]")
          val id2 = MemoryId.generate()
          store.store(Memory(id2, "test", MemoryType.Task))
          automaticRetryUpdate(store, id2)

          println("\n[Example 3: Concurrent Counter]")
          concurrentCounterExample(store)

          println("\n[Example 4: store() vs update()]")
          storeVsUpdateExample(store)

          println("\n[Example 5: State Machine]")
          val id5 = MemoryId.generate()
          stateMachineExample(store, id5)

          println("\n" + "=" * 60)
          println("  All examples completed!")
          println("=" * 60)

        } finally {
          store.close()
        }

      case Left(error) =>
        println(s"✗ Failed to initialize store: ${error.message}")
        println("  Make sure PostgreSQL is running:")
        println("  docker run --rm -p 5432:5432 -e POSTGRES_PASSWORD=password pgvector/pgvector:pg16")
    }
  }
}
