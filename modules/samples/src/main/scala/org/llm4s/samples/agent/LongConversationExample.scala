package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, ContextWindowConfig, PruningStrategy }
import org.llm4s.agent.graph.middleware.ContextWindowMiddleware
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Example demonstrating long multi-turn conversations with automatic context pruning.
 *
 * Shows how to use `runMultiTurn` and `ContextWindowConfig` to manage
 * conversation history automatically.
 */
object LongConversationExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Long Conversation with Context Pruning Example ===")

    // Configure context window management
    val contextConfig = ContextWindowConfig(
      maxMessages = Some(15),                       // Keep max 15 messages
      preserveSystemMessage = true,                 // Always keep system message
      minRecentTurns = 2,                           // Keep at least 2 recent turns
      pruningStrategy = PruningStrategy.OldestFirst // Drop oldest messages first
    )

    val result = for {
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      agent <- Agent
        .builder("long-conversation", client)
        .withTools(new ToolRegistry(Seq(weatherTool)))
        .withMiddleware(ContextWindowMiddleware(contextConfig)) // Enable automatic pruning
        .build()

      // Use runMultiTurn for convenience - automatically chains all turns
      finalState <- agent.runMultiTurn(
        first = "What's the weather in Paris?",
        followUps = Seq(
          "And in London?",
          "How about Tokyo?",
          "What about New York?",
          "And Sydney?",
          "Which of these cities is the warmest?",
          "Which is the coldest?",
          "Should I pack an umbrella for Paris?",
          "What about sunscreen for Sydney?"
        ),
      )

      _ = logger.info("=== Conversation Statistics ===")
      _ = logger.info("Total messages: {}", finalState.messages.length)
      _ = logger.info("User messages: {}", finalState.messages.count(_.role == MessageRole.User))
      _ = logger.info("Assistant messages: {}", finalState.messages.count(_.role == MessageRole.Assistant))
      _ = logger.info("Tool messages: {}", finalState.messages.count(_.role == MessageRole.Tool))

      _ = logger.info("=== Final Assistant Response ===")
      _ = logger.info("{}", AgentResults.answerOrStatus(finalState))

    } yield finalState

    result.fold(
      error => logger.error("Error: {}", error.formatted),
      state => logger.info("Success! Conversation completed with status: {}", state.status)
    )
  }
}
