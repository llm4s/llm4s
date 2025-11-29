package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentState }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating conversation persistence.
 *
 * Shows how to save and load agent state to/from disk,
 * enabling conversation resumption across sessions.
 */
object ConversationPersistenceExample {

  def main(args: Array[String]): Unit = {
    println("=== Conversation Persistence Example ===\n")

    val savePath = ".log/conversation-state.json"

    // Part 1: Start a conversation and save it
    val saveResult = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      tools = new ToolRegistry(Seq(WeatherTool.tool))
      agent = new Agent(client)

      _ = println("Part 1: Starting conversation and saving state\n")
      _ = println("Query: What's the weather in Paris?")

      state1 <- agent.run("What's the weather in Paris?", tools)
      _ = state1.conversation.messages
        .filter(_.role == MessageRole.Assistant)
        .lastOption
        .foreach(msg => println(s"Assistant: ${msg.content}"))

      _ = println(s"\nSaving state to: $savePath")
      _ <- AgentState.saveToFile(state1, savePath)
      _ = println("State saved successfully!")

    } yield state1

    // Part 2: Load the conversation and continue it
    val continueResult = for {
      _           <- saveResult // Wait for save to complete
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      tools = new ToolRegistry(Seq(WeatherTool.tool))
      agent = new Agent(client)

      _ = println("\n--- Simulating New Session ---\n")
      _ = println(s"Part 2: Loading state from: $savePath")

      loadedState <- AgentState.loadFromFile(savePath, tools)
      _ = println(s"State loaded! Conversation has ${loadedState.conversation.messageCount} messages")

      _ = println("\nContinuing conversation with: 'And what about London?'")
      state2 <- agent.continueConversation(loadedState, "And what about London?")
      _ = state2.conversation.messages
        .filter(_.role == MessageRole.Assistant)
        .lastOption
        .foreach(msg => println(s"Assistant: ${msg.content}"))

      _ = println(s"\n=== Final Statistics ===")
      _ = println(s"Total messages: ${state2.conversation.messageCount}")
      _ = println(s"Initial query: ${state2.initialQuery.getOrElse("N/A")}")
      _ = println(s"Status: ${state2.status}")

    } yield state2

    continueResult.fold(
      error => println(s"\nError: ${error.formatted}"),
      _ => println("\nSuccess! Conversation persisted and resumed.")
    )
  }
}
