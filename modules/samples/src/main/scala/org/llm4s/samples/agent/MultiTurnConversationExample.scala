package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

/**
 * Example demonstrating multi-turn conversations using the functional API.
 *
 * This shows how to use `continueConversation` to handle follow-up queries
 * without mutable state or imperative loops.
 */
object MultiTurnConversationExample {

  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    logger.info("=== Multi-Turn Conversation Example ===")

    // Functional style - no var, no mutation!
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      weatherTool <- WeatherTool.toolSafe
      tools = new ToolRegistry(Seq(weatherTool))
      agent = new Agent(client)

      _ = logger.info("Turn 1: Asking about Paris weather")
      state1 <- agent.run("What's the weather in Paris?", tools)
      _ = printLastAssistantMessage(state1)

      _ = logger.info("Turn 2: Asking about London weather")
      state2 <- agent.continueConversation(state1, "And what about London?")
      _ = printLastAssistantMessage(state2)

      _ = logger.info("Turn 3: Comparing the two")
      state3 <- agent.continueConversation(state2, "Which city is warmer?")
      _ = printLastAssistantMessage(state3)

      _ = logger.info("=== Conversation Complete ===")
      _ = logger.info("Total messages: {}", state3.conversation.messageCount)
      _ = logger.info("User messages: {}", state3.conversation.filterByRole(MessageRole.User).length)
      _ = logger.info("Assistant messages: {}", state3.conversation.filterByRole(MessageRole.Assistant).length)

    } yield state3

    result.fold(
      error => logger.error("Error: {}", error.formatted),
      _ => logger.info("Success!")
    )
  }

  private def printLastAssistantMessage(state: org.llm4s.agent.AgentState): Unit =
    state.conversation.messages
      .filter(_.role == MessageRole.Assistant)
      .lastOption
      .foreach(msg => logger.info("Assistant: {}", msg.content))
}
