package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentState }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Example demonstrating conversation persistence.
 *
 * Shows how to save and load agent state to/from disk,
 * enabling conversation resumption across sessions.
 */
object ConversationPersistenceExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Conversation Persistence Example ===")

    val savePath = ".log/conversation-state.json"

    // Part 1: Start a conversation and save it
    val saveResult = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      tools = new ToolRegistry(Seq(weatherTool))
      agent = new Agent(client)

      _ = logger.info("Part 1: Starting conversation and saving state")
      _ = logger.info("Query: What's the weather in Paris?")

      state1 <- agent.run("What's the weather in Paris?", tools)
      _ = state1.conversation.messages
        .filter(_.role == MessageRole.Assistant)
        .lastOption
        .foreach(msg => logger.info("Assistant: {}", msg.content))

      _ = logger.info("Saving state to: {}", savePath)
      _ <- AgentState.saveToFile(state1, savePath)
      _ = logger.info("State saved successfully!")

    } yield state1

    // Part 2: Load the conversation and continue it
    val continueResult = for {
      _            <- saveResult // Wait for save to complete
      providerCfg  <- Llm4sConfig.provider()
      client       <- LLMConnect.getClient(providerCfg)
      weatherTool2 <- WeatherTool.toolSafe
      tools = new ToolRegistry(Seq(weatherTool2))
      agent = new Agent(client)

      _ = logger.info("--- Simulating New Session ---")
      _ = logger.info("Part 2: Loading state from: {}", savePath)

      loadedState <- AgentState.loadFromFile(savePath, tools)
      _ = logger.info("State loaded! Conversation has {} messages", loadedState.conversation.messageCount)

      _ = logger.info("Continuing conversation with: 'And what about London?'")
      state2 <- agent.continueConversation(loadedState, "And what about London?")
      _ = state2.conversation.messages
        .filter(_.role == MessageRole.Assistant)
        .lastOption
        .foreach(msg => logger.info("Assistant: {}", msg.content))

      _ = logger.info("=== Final Statistics ===")
      _ = logger.info("Total messages: {}", state2.conversation.messageCount)
      _ = logger.info("Initial query: {}", state2.initialQuery.getOrElse("N/A"))
      _ = logger.info("Status: {}", state2.status)

    } yield state2

    continueResult.fold(
      error => logger.error("Error: {}", error.formatted),
      _ => logger.info("Success! Conversation persisted and resumed.")
    )
  }
}
