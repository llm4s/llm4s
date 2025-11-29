package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating multi-turn conversations using the functional API.
 *
 * This shows how to use `continueConversation` to handle follow-up queries
 * without mutable state or imperative loops.
 */
object MultiTurnConversationExample {

  def main(args: Array[String]): Unit = {
    println("=== Multi-Turn Conversation Example ===\n")

    // Functional style - no var, no mutation!
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      tools = new ToolRegistry(Seq(WeatherTool.tool))
      agent = new Agent(client)

      _ = println("Turn 1: Asking about Paris weather")
      state1 <- agent.run("What's the weather in Paris?", tools)
      _ = printLastAssistantMessage(state1)

      _ = println("\nTurn 2: Asking about London weather")
      state2 <- agent.continueConversation(state1, "And what about London?")
      _ = printLastAssistantMessage(state2)

      _ = println("\nTurn 3: Comparing the two")
      state3 <- agent.continueConversation(state2, "Which city is warmer?")
      _ = printLastAssistantMessage(state3)

      _ = println(s"\n=== Conversation Complete ===")
      _ = println(s"Total messages: ${state3.conversation.messageCount}")
      _ = println(s"User messages: ${state3.conversation.filterByRole(MessageRole.User).length}")
      _ = println(s"Assistant messages: ${state3.conversation.filterByRole(MessageRole.Assistant).length}")

    } yield state3

    result.fold(
      error => println(s"\nError: ${error.formatted}"),
      _ => println("\nSuccess!")
    )
  }

  private def printLastAssistantMessage(state: org.llm4s.agent.AgentState): Unit =
    state.conversation.messages
      .filter(_.role == MessageRole.Assistant)
      .lastOption
      .foreach(msg => println(s"Assistant: ${msg.content}"))
}
