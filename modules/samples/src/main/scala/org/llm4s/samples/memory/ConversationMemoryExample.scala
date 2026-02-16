package org.llm4s.samples.memory

import org.llm4s.agent.memory._
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory
import scala.util.chaining._

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
        logger.info("Recorded conversation with {} messages", conversation1.size)
        m.stats.foreach(s => logger.info("Total memories: {}", s.totalMemories))
      case Left(e) =>
        logger.error("Error recording first conversation: {}", e.message)
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
        logger.info("Recorded second conversation with {} messages", conversation2.size)
        m.stats.foreach(s => logger.info("Total memories: {}", s.totalMemories))
      case Left(e) =>
        logger.error("Error recording second conversation: {}", e.message)
    }

    // === Part 3: Retrieving Conversation History ===
    logger.info("3. Retrieving Conversation History")
    logger.info("-" * 40)

    val retrieveConv1 = withConv2.flatMap(m => m.getConversationContext("conv-scala-basics", maxMessages = 10))

    retrieveConv1.tap { result =>
      result.fold(
        // Handle Left (Error)
        e => logger.error("Error retrieving conversation history: {}", e.message),
        // Handle Right (Context)
        context => {
          val redacted = SensitiveDataRedactor.redact(context)
          val preview  = if (redacted.length > 300) redacted.take(300) + "... [truncated]" else redacted
          logger.info("Conversation 'conv-scala-basics' (preview, redacted):")
          logger.info("{}", preview)
        }
      )
    }

    // === Part 4: Filtering Conversation Messages ===
    logger.info("4. Filtering Conversation Messages")
    logger.info("-" * 40)

    withConv2.flatMap { m =>
      for {
        // Get only user messages
        _ <- m.store
          .recall(
            MemoryFilter.conversations && MemoryFilter.ByMetadata("role", "user")
          )
          .tap {
            case Right(msgs) =>
              logger.info("User messages across all conversations: {}", msgs.size)
              msgs.foreach(msg => logger.info("- {}...", msg.content.take(50)))
            case Left(e) => logger.error("Failed to recall user messages: {}", e.message)
          }

        // Get only assistant messages
        _ <- m.store
          .recall(
            MemoryFilter.conversations && MemoryFilter.ByMetadata("role", "assistant")
          )
          .tap {
            case Right(msgs) => logger.info("Assistant messages: {}", msgs.size)
            case Left(e)     => logger.error("Failed to recall assistant messages: {}", e.message)
          }

        // Get messages from a specific conversation
        _ <- m.store.recall(MemoryFilter.forConversation("conv-scala-basics")).tap {
          case Right(msgs) => logger.info("Messages in 'conv-scala-basics': {}", msgs.size)
          case Left(e)     => logger.error("Failed to recall conversation messages: {}", e.message)
        }
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
        m.getConversationContext("conv-traits").tap {
          case Right(ctx) =>
            val redacted = SensitiveDataRedactor.redact(ctx)
            val preview  = if (redacted.length > 300) redacted.take(300) + "... [truncated]" else redacted
            logger.info("Conversation context (preview, redacted):\n{}", preview)
          case Left(e) => logger.error("Failed to get conversation context: {}", e.message)
        }
      case Left(e) =>
        logger.error("Error recording incremental conversation: {}", e.message)
    }

    // === Part 6: Cross-Conversation Search ===
    logger.info("6. Cross-Conversation Search")
    logger.info("-" * 40)

    val searchResults = incrementalConv.flatMap { m =>
      for {
        // Search for pattern matching content across all conversations
        _ <- m.store.search("pattern matching", 3).tap {
          case Right(results) =>
            logger.info("Search results for 'pattern matching':")
            results.foreach { sr =>
              val convId = sr.memory.getMetadata("conversation_id").getOrElse("unknown")
              logger.info("[{}] ({}) {}...", sr.score, convId, sr.memory.content.take(40))
            }
          case Left(e) => logger.error("Search failed: {}", e.message)
        }

        // Search for Scala types
        _ <- m.store.search("case class trait", 3).tap {
          case Right(results) =>
            logger.info("Search results for 'case class trait':")
            results.foreach { sr =>
              val convId = sr.memory.getMetadata("conversation_id").getOrElse("unknown")
              logger.info("[{}] ({}) {}...", sr.score, convId, sr.memory.content.take(40))
            }
          case Left(e) => logger.error("Search failed: {}", e.message)
        }
      } yield ()
    }

    // === Part 7: Conversation Statistics ===
    logger.info("7. Conversation Statistics")
    logger.info("-" * 40)

    incrementalConv.foreach { m =>
      m.stats.foreach { stats =>
        logger.info("Total conversation messages: {}", stats.byType.getOrElse(MemoryType.Conversation, 0L))
        logger.info("Distinct conversations: {}", stats.conversationCount)
      }

      // Count messages per conversation
      logger.info("Messages per conversation:")
      Seq("conv-scala-basics", "conv-case-classes", "conv-traits").foreach { convId =>
        m.store.recall(MemoryFilter.forConversation(convId)).foreach { messages =>
          logger.info("{}: {} messages", convId, messages.size)
        }
      }
    }

    searchResults match {
      case Right(_) =>
        logger.info("=" * 50)
        logger.info("Conversation memory example completed successfully!")
      case Left(error) =>
        logger.error("Conversation example failed with error: {}", error.message)
    }
  }
}
