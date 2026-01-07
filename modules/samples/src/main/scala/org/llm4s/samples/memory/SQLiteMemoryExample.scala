package org.llm4s.samples.memory

import org.llm4s.agent.memory._
import org.slf4j.LoggerFactory

import java.nio.file.{ Files, Path }

/**
 * Example demonstrating persistent memory storage with SQLite.
 *
 * This example shows how to:
 * - Create a file-based SQLite memory store
 * - Persist memories across sessions
 * - Use full-text search
 * - Handle database lifecycle
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.memory.SQLiteMemoryExample"
 */
object SQLiteMemoryExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("SQLite Memory Store Example")
    logger.info("=" * 60)

    // Create a temporary database file
    val dbPath: Path = Files.createTempFile("llm4s-memory-", ".db")
    logger.info("Database location: {}", dbPath)

    // ============================================================
    // Part 1: Create store and populate with memories
    // ============================================================
    logger.info("--- Part 1: Creating and Populating Store ---")

    val createResult = for {
      store <- SQLiteMemoryStore(dbPath.toString)

      // Store various types of memories
      _ = logger.info("Storing knowledge memories...")
      _ <- store.store(
        Memory.fromKnowledge(
          "Scala is a multi-paradigm programming language ...",
          source = "scala-docs",
          chunkIndex = Some(1)
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Scala runs on the Java Virtual Machine (JVM) and is fully interoperable with Java.",
          source = "scala-docs",
          chunkIndex = Some(2)
        )
      )
      _ <- store.store(
        Memory.fromKnowledge(
          "Functional programming emphasizes immutability and pure functions without side effects.",
          source = "fp-guide",
          chunkIndex = Some(1)
        )
      )

      _       = logger.info("Storing entity memories...")
      scalaId = EntityId.fromName("Scala Language")
      _ <- store.store(Memory.forEntity(scalaId, "Scala Language", "Created by Martin Odersky", "programming_language"))
      _ <- store.store(Memory.forEntity(scalaId, "Scala Language", "First released in 2004", "programming_language"))

      _ = logger.info("Storing conversation memories...")
      _ <- store.store(Memory.fromConversation("What is Scala?", "user", Some("conv-1")))
      _ <- store.store(Memory.fromConversation("Scala is a programming language...", "assistant", Some("conv-1")))

      count <- store.count()
      _ = logger.info("Total memories stored: {}", count)

    } yield store

    createResult match {
      case Left(error) =>
        logger.error("Error creating store: {}", error)
        System.exit(1)

      case Right(store) =>
        // ============================================================
        // Part 2: Query the store
        // ============================================================
        logger.info("--- Part 2: Querying Memories ---")

        // Full-text search
        logger.info("Searching for 'functional programming'...")
        store.search("functional programming", 3) match {
          case Right(results) =>
            results.foreach(scored => logger.info("  Score: {} - {}...", scored.score, scored.memory.content.take(60)))
          case Left(error) =>
            logger.error("  Search error: {}", error)
        }

        // Filter by type
        logger.info("Knowledge memories:")
        store.recall(MemoryFilter.ByType(MemoryType.Knowledge)) match {
          case Right(memories) =>
            memories.foreach(m => logger.info("  - {}...", m.content.take(60)))
          case Left(error) =>
            logger.error("  Error: {}", error)
        }

        // Filter by entity
        logger.info("Scala entity facts:")
        store.recall(MemoryFilter.ByEntity(EntityId.fromName("Scala Language"))) match {
          case Right(memories) =>
            memories.foreach(m => logger.info("  - {}", m.content))
          case Left(error) =>
            logger.error("  Error: {}", error)
        }

        // ============================================================
        // Part 3: Persistence demonstration
        // ============================================================
        logger.info("--- Part 3: Demonstrating Persistence ---")
        logger.info("Closing database connection...")
        store.close()

        logger.info("Reopening database...")
        SQLiteMemoryStore(dbPath.toString) match {
          case Right(reopenedStore) =>
            reopenedStore.count() match {
              case Right(count) =>
                logger.info("Memories recovered from disk: {}", count)
              case Left(error) =>
                logger.error("Count error: {}", error)
            }

            logger.info("Searching recovered memories for 'JVM'...")
            reopenedStore.search("JVM", 2) match {
              case Right(results) =>
                results.foreach { scored =>
                  logger.info("  Score: {} - {}...", scored.score, scored.memory.content.take(60))
                }
              case Left(error) =>
                logger.error("  Search error: {}", error)
            }

            reopenedStore.close()

          case Left(error) =>
            logger.error("Error reopening store: {}", error)
        }

        // ============================================================
        // Part 4: Using with SimpleMemoryManager
        // ============================================================
        logger.info("--- Part 4: Using with SimpleMemoryManager ---")

        SQLiteMemoryStore(dbPath.toString) match {
          case Right(sqliteStore) =>
            // Clear for fresh start
            sqliteStore.clear()

            val config  = MemoryManagerConfig(defaultImportance = 0.5)
            val manager = SimpleMemoryManager.withStore(sqliteStore, config)

            val managerResult = for {
              m1 <- manager.recordKnowledge(
                "llm4s is a Scala library for building LLM applications",
                "readme",
                Map("version" -> "0.1")
              )
              m2 <- m1.recordKnowledge(
                "llm4s supports OpenAI, Anthropic, and Ollama providers",
                "readme",
                Map("section" -> "providers")
              )
              m3      <- m2.recordUserFact("Prefers functional programming style", Some("user-1"), None)
              context <- m3.getRelevantContext("Tell me about llm4s")
            } yield context

            managerResult match {
              case Right(context) =>
                logger.info("Relevant context for 'Tell me about llm4s':")
                logger.info("  {}", context)
              case Left(error) =>
                logger.error("Manager error: {}", error)
            }

            sqliteStore.close()

          case Left(error) =>
            logger.error("Error: {}", error)
        }
    }

    // Cleanup
    logger.info("--- Cleanup ---")
    Files.deleteIfExists(dbPath)
    logger.info("Deleted temporary database: {}", dbPath)

    logger.info("=" * 60)
    logger.info("SQLite Memory Example Complete!")
    logger.info("=" * 60)
  }
}
