package org.llm4s.samples.memory

import org.llm4s.agent.Agent
import org.llm4s.agent.memory._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry

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
object MemoryWithAgentExample extends App {
  println("=== Memory with Agent Integration Example ===\n")

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // === Part 1: Initialize Memory with Background Knowledge ===
    _ = println("1. Setting up memory with background knowledge")
    _ = println("-" * 40)

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
    m5 <- m4.recordKnowledge(
      "The API uses http4s server with JSON serialization via Circe",
      "project-docs",
      Map("component" -> "api")
    )

    _ = println(s"Initialized memory with ${m5.stats.map(_.totalMemories).getOrElse(0)} items")

    // === Part 2: Build Context-Aware Prompt ===
    _ = println("\n2. Building context-aware prompt")
    _ = println("-" * 40)

    userContext <- m5.getUserContext(Some("current-user"))
    _ = println("User context:\n" + userContext)

    relevantContext <- m5.getRelevantContext("help me with error handling", maxTokens = 500)
    _ = println("\nRelevant knowledge:\n" + relevantContext)

    // Build the system prompt with memory context
    systemPrompt = s"""You are a helpful programming assistant. Here's what you know about the user:

$userContext

Here's relevant project context:
$relevantContext

Provide responses tailored to the user's experience level and preferences."""

    // === Part 3: Run Agent with Memory Context ===
    _ = println("\n3. Running agent with memory context")
    _ = println("-" * 40)

    state1 <- agent.run(
      query = "How should I handle errors in my API endpoints?",
      tools = ToolRegistry.empty,
      systemPromptAddition = Some(systemPrompt)
    )

    response1 = state1.conversation.messages.last.content
    _         = println("Agent response (context-aware):")
    _         = println(response1.take(500) + (if (response1.length > 500) "..." else ""))

    // === Part 4: Record the Conversation ===
    _ = println("\n4. Recording the conversation in memory")
    _ = println("-" * 40)

    m6 <- m5.recordConversation(state1.conversation.messages.toSeq, "session-1")

    stats <- m6.stats
    _ = println(s"Memory now contains:")
    _ = println(s"  Total memories: ${stats.totalMemories}")
    _ = println(s"  Conversation messages: ${stats.byType.getOrElse(MemoryType.Conversation, 0L)}")
    _ = println(s"  Distinct conversations: ${stats.conversationCount}")

    // === Part 5: Continue with Memory ===
    _ = println("\n5. Continuing conversation with full history")
    _ = println("-" * 40)

    // Get previous conversation context
    _ <- m6.getConversationContext("session-1", maxMessages = 10)
    _ = println("Previous conversation retrieved from memory")

    // Continue the conversation
    state2 <- agent.continueConversation(
      state1,
      "What about validation errors specifically?"
    )

    response2 = state2.conversation.messages.last.content
    _         = println("\nFollow-up response:")
    _         = println(response2.take(500) + (if (response2.length > 500) "..." else ""))

    // Record follow-up
    m7 <- m6.recordMessage(
      state2.conversation.messages.last,
      "session-1",
      Some(0.8)
    )

    // === Part 6: Extract Key Facts ===
    _ = println("\n6. Summary of what the agent learned")
    _ = println("-" * 40)

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
    finalStats <- m9.stats
    _ = println(s"\nFinal memory state:")
    _ = println(s"  Total memories: ${finalStats.totalMemories}")
    _ = finalStats.byType.foreach { case (t, c) =>
      println(s"  ${t.name}: $c")
    }

  } yield ()

  result match {
    case Right(_) =>
      println("\n" + "=" * 50)
      println("Memory with agent example completed successfully!")
      println("\nKey takeaways:")
      println("  - Memory provides persistent context across interactions")
      println("  - User facts personalize responses")
      println("  - Knowledge retrieval enables domain-specific answers")
      println("  - Conversation history maintains coherent multi-turn dialogue")

    case Left(error) =>
      println(s"\nExample failed with error:")
      println(s"  ${error.formatted}")
      println("\nMake sure LLM_MODEL and API key are configured.")
  }
}
