package org.llm4s.samples.memory

import org.llm4s.agent.memory._
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating conversation memory management.
 *
 * This example shows how to:
 * - Record conversation messages
 * - Retrieve conversation history
 * - Manage multiple conversations
 * - Format conversation context for LLM prompts
 *
 * No external dependencies required - runs entirely in-memory.
 */
object ConversationMemoryExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Conversation Memory Example ===")

    // === Part 1: Recording a Conversation ===
    logger.info("1. Recording a Conversation")
    logger.info("-" * 40)

    val conversation1 = Seq(
      SystemMessage("You are a helpful programming assistant."),
      UserMessage("What's the difference between val and var in Scala?"),
      AssistantMessage("""In Scala:
        |- `val` creates an immutable reference (like Java's final)
        |- `var` creates a mutable reference
        |Best practice: prefer `val` for immutability.""".stripMargin),
      UserMessage("Can you show me an example?"),
      AssistantMessage("""Sure! Here's an example:
        |```scala
        |val x = 10    // Cannot reassign: x = 20 would fail
        |var y = 10    // Can reassign: y = 20 works
        |```""".stripMargin)
    )

    val manager   = SimpleMemoryManager.empty
    val withConv1 = manager.recordConversation(conversation1, "conv-scala-basics")

    withConv1 match {
      case Right(m) =>
        logger.info(s"Recorded conversation with ${conversation1.size} messages")
        m.stats.foreach(s => logger.info(s"Total memories: ${s.totalMemories}"))
      case Left(e) =>
        logger.error(s"Error recording first conversation: ${e.message}")
    }

    // === Part 2: Recording Another Conversation ===
    logger.info("2. Recording Another Conversation")
    logger.info("-" * 40)

    val conversation2 = Seq(
      SystemMessage("You are a friendly coding tutor."),
      UserMessage("How do I create a case class?"),
      AssistantMessage("""To create a case class in Scala:
        |```scala
        |case class Person(name: String, age: Int)
        |```
        |Case classes automatically get: equals, hashCode, toString, copy, and more!""".stripMargin),
      UserMessage("What about pattern matching?"),
      AssistantMessage("""Case classes work great with pattern matching:
        |```scala
        |person match {
        |  case Person("Alice", _) => "Found Alice!"
        |  case Person(_, age) if age > 18 => "An adult"
        |  case _ => "Someone else"
        |}
        |```""".stripMargin)
    )

    val withConv2 = withConv1.flatMap(_.recordConversation(conversation2, "conv-case-classes"))

    withConv2 match {
      case Right(m) =>
        logger.info(s"Recorded second conversation with ${conversation2.size} messages")
        m.stats.foreach(s => logger.info(s"Total memories: ${s.totalMemories}"))
      case Left(e) =>
        logger.error(s"Error recording second conversation: ${e.message}")
    }

    // === Part 3: Retrieving Conversation History ===
    logger.info("3. Retrieving Conversation History")
    logger.info("-" * 40)

    val retrieveConv1 = withConv2.flatMap(m => m.getConversationContext("conv-scala-basics", maxMessages = 10))

    retrieveConv1 match {
      case Right(context) =>
        logger.info("Conversation 'conv-scala-basics':")
        logger.info(context)
      case Left(e) =>
        logger.error(s"Error retrieving conversation history: ${e.message}")
    }

    // === Part 4: Filtering Conversation Messages ===
    logger.info("4. Filtering Conversation Messages")
    logger.info("-" * 40)

    val _ = withConv2.flatMap { m =>
      for {
        // Get only user messages
        userMessages <- m.store.recall(
          MemoryFilter.conversations && MemoryFilter.ByMetadata("role", "user")
        )
        _ = logger.info(s"User messages across all conversations: ${userMessages.size}")
        _ = userMessages.foreach(msg => logger.info(s"- ${msg.content.take(50)}..."))

        // Get only assistant messages
        assistantMessages <- m.store.recall(
          MemoryFilter.conversations && MemoryFilter.ByMetadata("role", "assistant")
        )
        _ = logger.info(s"Assistant messages: ${assistantMessages.size}")

        // Get messages from a specific conversation
        conv1Messages <- m.store.recall(MemoryFilter.forConversation("conv-scala-basics"))
        _ = logger.info(s"Messages in 'conv-scala-basics': ${conv1Messages.size}")
      } yield ()
    }

    // === Part 5: Recording Single Messages ===
    logger.info("5. Recording Single Messages (Incremental)")
    logger.info("-" * 40)

    val incrementalConv = withConv2.flatMap { m =>
      for {
        m1 <- m.recordMessage(UserMessage("What is a trait?"), "conv-traits", None)
        m2 <- m1.recordMessage(
          AssistantMessage("A trait is similar to an interface but can have concrete implementations."),
          "conv-traits",
          Some(0.8) // High importance message
        )
        m3 <- m2.recordMessage(UserMessage("Can I mix in multiple traits?"), "conv-traits", None)
        m4 <- m3.recordMessage(
          AssistantMessage("Yes! Scala supports mixin composition with multiple traits."),
          "conv-traits",
          Some(0.8)
        )
      } yield m4
    }

    incrementalConv match {
      case Right(m) =>
        logger.info("Recorded incremental conversation about traits")
        m.getConversationContext("conv-traits").foreach(ctx => logger.info(ctx))
      case Left(e) =>
        logger.error(s"Error recording incremental conversation: ${e.message}")
    }

    // === Part 6: Cross-Conversation Search ===
    logger.info("6. Cross-Conversation Search")
    logger.info("-" * 40)

    val searchResults = incrementalConv.flatMap { m =>
      for {
        // Search for pattern matching content across all conversations
        patternMatching <- m.store.search("pattern matching", topK = 3)
        _ = logger.info("Search results for 'pattern matching':")
        _ = patternMatching.foreach { sr =>
          val convId = sr.memory.getMetadata("conversation_id").getOrElse("unknown")
          logger.info(f"[${sr.score}%.2f] ($convId) ${sr.memory.content.take(40)}...")
        }

        // Search for Scala types
        scalaTypes <- m.store.search("case class trait", topK = 3)
        _ = logger.info("Search results for 'case class trait':")
        _ = scalaTypes.foreach { sr =>
          val convId = sr.memory.getMetadata("conversation_id").getOrElse("unknown")
          logger.info(f"[${sr.score}%.2f] ($convId) ${sr.memory.content.take(40)}...")
        }
      } yield ()
    }

    // === Part 7: Conversation Statistics ===
    logger.info("7. Conversation Statistics")
    logger.info("-" * 40)

    incrementalConv.foreach { m =>
      m.stats.foreach { stats =>
        logger.info(s"Total conversation messages: ${stats.byType.getOrElse(MemoryType.Conversation, 0L)}")
        logger.info(s"Distinct conversations: ${stats.conversationCount}")
      }

      // Count messages per conversation
      logger.info("Messages per conversation:")
      Seq("conv-scala-basics", "conv-case-classes", "conv-traits").foreach { convId =>
        m.store.recall(MemoryFilter.forConversation(convId)).foreach { messages =>
          logger.info(s"$convId: ${messages.size} messages")
        }
      }
    }

    searchResults match {
      case Right(_) =>
        logger.info("=" * 50)
        logger.info("Conversation memory example completed successfully!")
      case Left(error) =>
        logger.error(s"Conversation example failed with error: ${error.message}")
    }
  }
}
