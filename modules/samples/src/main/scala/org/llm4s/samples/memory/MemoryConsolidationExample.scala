package org.llm4s.samples.memory

import org.llm4s.agent.memory._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Example demonstrating LLM-powered memory consolidation.
 *
 * This example shows how to:
 * 1. Create an LLMMemoryManager with LLM-powered consolidation
 * 2. Populate memories (conversations, user facts, entity facts, knowledge)
 * 3. Consolidate memories to reduce redundancy
 * 4. Examine consolidated results and metadata
 *
 * The consolidation uses an LLM to intelligently summarize related memories.
 *
 * To run this example:
 * {{{
 * sbt "samples/runMain org.llm4s.samples.memory.MemoryConsolidationExample"
 * }}}
 *
 * Make sure your LLM provider is configured (see Llm4sConfig)
 */
object MemoryConsolidationExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=" * 60)
    logger.info("Memory Consolidation Example")
    logger.info("=" * 60)

    val result = for {
      providerConfig <- Llm4sConfig.provider()
      client         <- LLMConnect.getClient(providerConfig)
    } yield runExample(client, providerConfig.model)

    result match {
      case Right(success) =>
        logger.info("\n" + "=" * 60)
        if (success) {
          logger.info("✅ Memory consolidation example completed successfully")
        } else {
          logger.info("⚠️  Example completed with warnings")
        }
        logger.info("=" * 60)

      case Left(err) =>
        logger.error("\n" + "=" * 60)
        logger.error("❌ Error: {}", err.toString)
        logger.error("=" * 60)
        System.exit(1)
    }
  }

  private def runExample(client: LLMClient, modelName: String): Boolean = {
    logger.info("--- Part 1: Setting up LLM Memory Manager ---")
    val store                  = InMemoryStore.empty
    var manager: MemoryManager = LLMMemoryManager.withDefaults(store, client)
    logger.info("Created LLMMemoryManager with {} model", modelName)

    // Part 2: Populate with conversation memories
    logger.info("\n--- Part 2: Populating with Conversation Memories ---")
    val conversation1 = Seq(
      UserMessage("What is functional programming?"),
      AssistantMessage(
        "Functional programming is a paradigm that treats computation as the evaluation of mathematical functions."
      ),
      UserMessage("Can you give me a Scala example?"),
      AssistantMessage("Sure! Here's a simple example: val doubled = List(1,2,3).map(_ * 2)")
    )

    manager = manager.recordConversation(conversation1, "conv-scala-basics").getOrElse(manager)
    logger.info("Recorded conversation 1 with {} messages", conversation1.length)

    val conversation2 = Seq(
      UserMessage("How do I handle errors in Scala?"),
      AssistantMessage("Scala provides Try, Either, and Option for error handling."),
      UserMessage("Which one should I use?"),
      AssistantMessage("Use Option for missing values, Either for errors, and Try for exceptions.")
    )

    manager = manager.recordConversation(conversation2, "conv-error-handling").getOrElse(manager)
    logger.info("Recorded conversation 2 with {} messages", conversation2.length)

    // Part 3: Populate with user facts
    logger.info("\n--- Part 3: Adding User Facts ---")
    val userFacts = Seq(
      ("Expert Scala developer", 0.9),
      ("Prefers functional programming style", 0.8),
      ("Works in fintech industry", 0.7),
      ("Based in San Francisco", 0.6)
    )

    for ((fact, importance) <- userFacts)
      manager = manager.recordUserFact(fact, Some("user-1"), Some(importance)).getOrElse(manager)
    logger.info("Recorded {} user facts", userFacts.length)

    // Part 4: Populate with entity facts
    logger.info("\n--- Part 4: Adding Entity Facts ---")
    val entityId = EntityId.fromName("Scala")
    val entityFacts = Seq(
      ("Created by Martin Odersky in 2004", 0.9),
      ("Runs on the JVM", 0.8),
      ("Supports both OOP and FP", 0.9),
      ("Version 3 introduced union types", 0.7)
    )

    for ((fact, importance) <- entityFacts)
      manager = manager.recordEntityFact(entityId, "Scala", fact, "technology", Some(importance)).getOrElse(manager)
    logger.info("Recorded {} entity facts about Scala", entityFacts.length)

    // Part 5: Populate with knowledge entries
    logger.info("\n--- Part 5: Adding Knowledge Entries ---")
    val knowledge = Seq(
      ("Immutability is a core principle in functional programming", Map("chapter" -> "1")),
      ("Pure functions have no side effects", Map("chapter" -> "1")),
      ("Higher-order functions take other functions as parameters", Map("chapter" -> "2"))
    )

    for ((content, metadata) <- knowledge)
      manager = manager.recordKnowledge(content, "fp-guide.md", metadata).getOrElse(manager)
    logger.info("Recorded {} knowledge entries", knowledge.length)

    // Part 6: Show stats before consolidation
    logger.info("\n--- Part 6: Memory Statistics Before Consolidation ---")
    manager.stats.foreach { statsBefore =>
      logger.info("Total memories: {}", statsBefore.totalMemories)
      statsBefore.byType.foreach { case (memType, count) =>
        logger.info("  {}: {}", memType.name, count)
      }
      logger.info("Oldest memory: {}", statsBefore.oldestMemory.getOrElse("N/A"))
      logger.info("Newest memory: {}", statsBefore.newestMemory.getOrElse("N/A"))
    }

    // Part 7: Consolidate memories
    logger.info("\n--- Part 7: Consolidating Memories ---")
    logger.info("Consolidating memories older than now (all memories)...")
    logger.info("Minimum memories per group: 3")

    manager = manager
      .consolidateMemories(
        olderThan = Instant.now().plus(1, ChronoUnit.DAYS), // Include all memories
        minCount = 3                                        // Only consolidate groups with 3+ memories
      )
      .getOrElse(manager)

    logger.info("✅ Consolidation completed")

    // Part 8: Show stats after consolidation
    logger.info("\n--- Part 8: Memory Statistics After Consolidation ---")
    manager.stats.foreach { statsAfter =>
      logger.info("Total memories: {}", statsAfter.totalMemories)
      statsAfter.byType.foreach { case (memType, count) =>
        logger.info("  {}: {}", memType.name, count)
      }
    }

    // Part 9: Examine consolidated memories
    logger.info("\n--- Part 9: Examining Consolidated Memories ---")
    manager.store.recall(MemoryFilter.All, 100).foreach { allMemories =>
      allMemories.foreach { memory =>
        logger.info("\n[{}] {}", memory.memoryType.name, memory.id.value.take(8))
        val redacted = SensitiveDataRedactor.redact(memory.content)
        val preview  = if (redacted.length > 100) redacted.take(100) + "..." else redacted
        logger.info("Content (preview, redacted): {}", preview)

        memory.getMetadata("consolidated_from").foreach { count =>
          logger.info("  ↳ Consolidated from {} memories", count)
        }
        memory.getMetadata("consolidation_method").foreach(method => logger.info("  ↳ Method: {}", method))
        memory.importance.foreach(imp => logger.info("  ↳ Importance: {}", imp))
      }
    }

    // Part 10: Test retrieval after consolidation
    logger.info("\n--- Part 10: Testing Context Retrieval After Consolidation ---")
    manager.getUserContext(Some("user-1")).foreach { userContext =>
      val redacted = SensitiveDataRedactor.redact(userContext)
      val preview  = if (redacted.length > 200) redacted.take(200) + "... [truncated]" else redacted
      logger.info("User context (preview, redacted):\n{}", preview)
    }

    manager.getEntityContext(entityId).foreach { entityContext =>
      val redacted = SensitiveDataRedactor.redact(entityContext)
      val preview  = if (redacted.length > 200) redacted.take(200) + "... [truncated]" else redacted
      logger.info("\nEntity context (preview, redacted):\n{}", preview)
    }

    true
  }
}
