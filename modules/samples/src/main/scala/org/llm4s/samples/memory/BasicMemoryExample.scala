package org.llm4s.samples.memory

import org.llm4s.agent.memory._
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating basic memory system usage.
 *
 * This example shows how to:
 * - Create a memory manager
 * - Store different types of memories (user facts, entities, knowledge)
 * - Recall memories with filters
 * - Search memories by keyword
 *
 * No external dependencies required - runs entirely in-memory.
 */
object BasicMemoryExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Basic Memory System Example ===")

    // === Part 1: Creating a Memory Manager ===
    logger.info("1. Creating a Memory Manager")
    logger.info("-" * 40)

    // Start with an empty manager using default in-memory storage
    val manager = SimpleMemoryManager.empty
    logger.info("Created empty SimpleMemoryManager")
    logger.info("Initial memory count: {}", manager.stats.map(_.totalMemories).getOrElse(0))

    // === Part 2: Storing Different Memory Types ===
    logger.info("2. Storing Different Memory Types")
    logger.info("-" * 40)

    // Store user preferences
    val withUserFacts = for {
      m1 <- manager.recordUserFact("Prefers Scala over Java", Some("user-123"), Some(0.9))
      m2 <- m1.recordUserFact("Works in fintech industry", Some("user-123"), Some(0.8))
      m3 <- m2.recordUserFact("Based in San Francisco", Some("user-123"), Some(0.7))
    } yield m3

    withUserFacts match {
      case Right(m) =>
        logger.info("Stored 3 user facts")
        m.stats.foreach(s => logger.info("  Total memories: {}", s.totalMemories))
      case Left(e) =>
        logger.error("Error while storing user facts: {}", e.message)
    }

    // Store entity information
    val entityId = EntityId.fromName("Scala Programming")
    val withEntities = withUserFacts.flatMap { m =>
      for {
        m1 <- m.recordEntityFact(entityId, "Scala", "Created by Martin Odersky in 2004", "technology", Some(0.9))
        m2 <- m1.recordEntityFact(entityId, "Scala", "Runs on the JVM", "technology", Some(0.8))
        m3 <- m2.recordEntityFact(entityId, "Scala", "Supports both OOP and FP paradigms", "technology", Some(0.9))
      } yield m3
    }

    withEntities match {
      case Right(m) =>
        logger.info("Stored 3 entity facts about Scala")
        m.stats.foreach(s => logger.info("  Total memories: {}", s.totalMemories))
      case Left(e) =>
        logger.error("Error while storing entity facts: {}", e.message)
    }

    // Store knowledge from documents
    val withKnowledge = withEntities.flatMap { m =>
      for {
        m1 <- m.recordKnowledge(
          "Functional programming emphasizes immutability and pure functions",
          "docs/fp-intro.md",
          Map("chapter" -> "1", "topic" -> "basics")
        )
        m2 <- m1.recordKnowledge(
          "Scala 3 introduced union types and intersection types",
          "docs/scala3-features.md",
          Map("chapter" -> "2", "topic" -> "types")
        )
      } yield m2
    }

    withKnowledge match {
      case Right(m) =>
        logger.info("Stored 2 knowledge entries")
        m.stats.foreach(s => logger.info("  Total memories: {}", s.totalMemories))
      case Left(e) =>
        logger.error("Error while storing knowledge entries: {}", e.message)
    }

    // === Part 3: Recalling Memories with Filters ===
    logger.info("3. Recalling Memories with Filters")
    logger.info("-" * 40)

    val filterExamples = withKnowledge.flatMap { m =>
      for {
        // Get all user facts
        _ <- m.store.recall(MemoryFilter.userFacts).tap {
          case Right(facts) =>
            logger.info("User facts ({}):", facts.size)
            facts.foreach(f => logger.info("  - {}", f.content))
          case Left(e) =>
            logger.error("Failed to recall user facts: {}", e.message)
        }

        // Get all entity facts
        _ <- m.store.recall(MemoryFilter.entities).tap {
          case Right(facts) =>
            logger.info("Entity facts ({}):", facts.size)
            facts.foreach(f => logger.info("  - {}", f.content))
          case Left(e) =>
            logger.error("Failed to recall entity facts: {}", e.message)
        }

        // Get high-importance memories
        _ <- m.store.important(threshold = 0.85).tap {
          case Right(imps) =>
            logger.info("High importance memories (score >= 0.85): {}", imps.size)
            imps.foreach(f => logger.info("  - {} (importance: {})", f.content, f.importance.getOrElse(0.0)))
          case Left(e) =>
            logger.error("Failed to recall important memories: {}", e.message)
        }

        // Combine filters: user facts OR knowledge
        _ <- m.store.recall(MemoryFilter.userFacts || MemoryFilter.knowledge).tap {
          case Right(res) => logger.info("User facts OR knowledge: {} memories", res.size)
          case Left(e)    => logger.error("Failed to recall combined memories: {}", e.message)
        }
      } yield m
    }

    // === Part 4: Searching Memories ===
    logger.info("4. Searching Memories by Keyword")
    logger.info("-" * 40)

    val searchExamples = filterExamples.flatMap { m =>
      for {
        // Search for Scala-related content
        _ <- m.store.search("Scala JVM", 5).tap {
          case Right(results) =>
            logger.info("Search results for 'Scala JVM':")
            results.foreach(sr => logger.info("  Score: {} - {}...", sr.score, sr.memory.content.take(50)))
          case Left(e) => logger.error("Search failed: {}", e.message)
        }

        // Search for FP content
        _ <- m.store.search("functional programming", 5).tap {
          case Right(results) =>
            logger.info("Search results for 'functional programming':")
            results.foreach(sr => logger.info("  Score: {} - {}...", sr.score, sr.memory.content.take(50)))
          case Left(e) => logger.error("Search failed: {}", e.message)
        }
      } yield m
    }

    // === Part 5: Getting Context ===
    logger.info("5. Getting Formatted Context")
    logger.info("-" * 40)

    val contextExamples = searchExamples.flatMap { m =>
      for {
        // Get entity context
        _ <- m.getEntityContext(entityId).tap {
          case Right(ctx) =>
            val redacted = SensitiveDataRedactor.redact(ctx)
            val preview  = if (redacted.length > 200) redacted.take(200) + "... [truncated]" else redacted
            logger.info("Entity context for 'Scala' (preview, redacted):")
            logger.info("{}", if (preview.nonEmpty) preview else "  (none)")
          case Left(e) => logger.error("Failed to get entity context: {}", e.message)
        }

        // Get user context
        _ <- m.getUserContext(Some("user-123")).tap {
          case Right(ctx) =>
            val redacted = SensitiveDataRedactor.redact(ctx)
            val preview  = if (redacted.length > 200) redacted.take(200) + "... [truncated]" else redacted
            logger.info("User context (preview, redacted):")
            logger.info("{}", if (preview.nonEmpty) preview else "  (none)")
          case Left(e) => logger.error("Failed to get user context: {}", e.message)
        }

        // Get relevant context for a query
        _ <- m.getRelevantContext("Tell me about Scala and FP", 500).tap {
          case Right(ctx) =>
            val redacted = SensitiveDataRedactor.redact(ctx)
            val preview  = if (redacted.length > 300) redacted.take(300) + "... [truncated]" else redacted
            logger.info("Relevant context for 'Tell me about Scala and FP' (preview, redacted):")
            logger.info("{}", if (preview.nonEmpty) preview else "  (none)")
          case Left(e) => logger.error("Failed to get relevant context: {}", e.message)
        }
      } yield ()
    }

    // === Part 6: Statistics ===
    logger.info("6. Memory Statistics")
    logger.info("-" * 40)

    filterExamples.foreach { m =>
      m.stats.foreach { stats =>
        logger.info(
          """
          |Memory Statistics:
          |  Total memories: {}
          |  By type:
          |{}
          |  Distinct entities: {}
          |  Embedded: {}
          """.stripMargin,
          stats.totalMemories,
          stats.byType.map { case (t, c) => s"    - ${t.name}: $c" }.mkString("\n"),
          stats.entityCount,
          stats.embeddedCount
        )
      }
    }

    contextExamples match {
      case Right(_) =>
        logger.info("=" * 50)
        logger.info("Basic memory example completed successfully!")
      case Left(error) =>
        logger.error("Example failed with error: {}", error.message)
    }
  }
}
