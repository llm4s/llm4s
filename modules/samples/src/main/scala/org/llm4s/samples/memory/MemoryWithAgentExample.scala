package org.llm4s.samples.memory

import org.llm4s.agent.Agent
import org.llm4s.agent.memory._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory
import scala.util.chaining._

/**
 * Example demonstrating memory integration with the Agent API.
 *
 * This example shows how to:
 * - Use memory to provide context to an agent
 * - Record agent conversations automatically
 * - Build up knowledge over multiple interactions
 * - Use memory for context-aware responses
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object MemoryWithAgentExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Memory with Agent Integration Example ===")

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      agent = new Agent(client)

      // === Part 1: Initialize Memory with Background Knowledge ===
      _ = logger.info("1. Setting up memory with background knowledge")
      _ = logger.info("-" * 40)

      initialManager = SimpleMemoryManager.empty

      // Store some user facts
      m1 <- initialManager.recordUserFact(
        "The user is a Scala developer with 3 years experience",
        Some("current-user"),
        Some(0.9)
      )
      m2 <- m1.recordUserFact(
        "The user prefers functional programming style",
        Some("current-user"),
        Some(0.8)
      )
      m3 <- m2.recordUserFact(
        "The user works on a fintech application",
        Some("current-user"),
        Some(0.7)
      )

      // Store some knowledge about the project
      m4 <- m3.recordKnowledge(
        "The project uses Cats Effect for async programming",
        "project-docs",
        Map("component" -> "core")
      )
      m5 <- m4
        .recordKnowledge(
          "The API uses http4s server with JSON serialization via Circe",
          "project-docs",
          Map("component" -> "api")
        )
        .tap {
          case Right(m) => logger.info("Initialized memory with {} items", m.stats.map(_.totalMemories).getOrElse(0))
          case Left(e)  => logger.error("Failed to init memory: {}", e.message)
        }

      // === Part 2: Build Context-Aware Prompt ===
      _ = logger.info("2. Building context-aware prompt")
      _ = logger.info("-" * 40)

      userContext <- m5.getUserContext(Some("current-user")).tap {
        case Right(ctx) =>
          val redacted = SensitiveDataRedactor.redact(ctx)
          val preview  = if (redacted.length > 200) redacted.take(200) + "... [truncated]" else redacted
          logger.info("User context (preview, redacted):\n{}", preview)
        case Left(e) => logger.error("Failed to get user context: {}", e.message)
      }

      relevantContext <- m5.getRelevantContext("help me with error handling", 500).tap {
        case Right(ctx) =>
          val redacted = SensitiveDataRedactor.redact(ctx)
          val preview  = if (redacted.length > 300) redacted.take(300) + "... [truncated]" else redacted
          logger.info("Relevant knowledge (preview, redacted):\n{}", preview)
        case Left(e) => logger.error("Failed to get relevant context: {}", e.message)
      }

      // Build the system prompt with memory context
      systemPrompt = s"""You are a helpful programming assistant. Here's what you know about the user:

$userContext

Here's relevant project context:
$relevantContext

Provide responses tailored to the user's experience level and preferences."""

      // === Part 3: Run Agent with Memory Context ===
      _ = logger.info("3. Running agent with memory context")
      _ = logger.info("-" * 40)

      state1 <- agent.run(
        query = "How should I handle errors in my API endpoints?",
        tools = ToolRegistry.empty,
        systemPromptAddition = Some(systemPrompt)
      )

      _ = {
        val r = state1.conversation.messages.last.content
        logger.info("Agent response (context-aware):")
        logger.info("{}", r.take(500) + (if (r.length > 500) "..." else ""))
      }

      // === Part 4: Record the Conversation ===
      _ = logger.info("4. Recording the conversation in memory")
      _ = logger.info("-" * 40)

      m6 <- m5.recordConversation(state1.conversation.messages.toSeq, "session-1")

      _ <- m6.stats.tap {
        case Right(s) =>
          logger.info("Memory now contains:")
          logger.info("  Total memories: {}", s.totalMemories)
          logger.info("  Conversation messages: {}", s.byType.getOrElse(MemoryType.Conversation, 0L))
          logger.info("  Distinct conversations: {}", s.conversationCount)
        case Left(e) =>
          logger.warn("Stats not available: {}", e.message)
      }

      // === Part 5: Continue with Memory ===
      _ = logger.info("5. Continuing conversation with full history")
      _ = logger.info("-" * 40)

      // Get previous conversation context
      _ <- m6.getConversationContext("session-1", maxMessages = 10).tap {
        case Right(_) => logger.info("Previous conversation retrieved from memory")
        case Left(e)  => logger.error("Failed to get conversation: {}", e.message)
      }

      // Continue the conversation
      state2 <- agent.continueConversation(
        state1,
        "What about validation errors specifically?"
      )

      _ = {
        val r = state2.conversation.messages.last.content
        logger.info("Follow-up response:")
        logger.info("{}", r.take(500) + (if (r.length > 500) "..." else ""))
      }

      // Record follow-up
      m7 <- m6.recordMessage(
        state2.conversation.messages.last,
        "session-1",
        Some(0.8)
      )

      // === Part 6: Extract Key Facts ===
      _ = logger.info("6. Summary of what the agent learned")
      _ = logger.info("-" * 40)

      // In a real system, you might use the LLM to extract key facts
      // For now, we record manually as an example
      m8 <- m7.recordEntityFact(
        EntityId.fromName("error-handling-pattern"),
        "Error Handling Pattern",
        "Use Either[AppError, A] for typed error handling in API endpoints",
        "pattern",
        Some(0.9)
      )
      m9 <- m8.recordEntityFact(
        EntityId.fromName("validation-pattern"),
        "Validation Pattern",
        "Use ValidatedNel for accumulating validation errors",
        "pattern",
        Some(0.9)
      )

      // Show final state
      _ <- m9.stats.tap {
        case Right(s) =>
          logger.info("Final memory state:")
          logger.info("  Total memories: {}", s.totalMemories)
          s.byType.foreach { case (t, c) => logger.info("  {}: {}", t.name, c) }
        case Left(e) =>
          logger.warn("Final stats not available: {}", e.message)
      }

    } yield ()

    result match {
      case Right(_) =>
        logger.info("=" * 50)
        logger.info("Memory with agent example completed successfully!")
        logger.info("Key takeaways:")
        logger.info("  - Memory provides persistent context across interactions")
        logger.info("  - User facts personalize responses")
        logger.info("  - Knowledge retrieval enables domain-specific answers")
        logger.info("  - Conversation history maintains coherent multi-turn dialogue")

      case Left(error) =>
        logger.error("Example failed with error:")
        logger.error("  {}", error.formatted)
        logger.error("Make sure LLM_MODEL and API key are configured.")
    }
  }
}
