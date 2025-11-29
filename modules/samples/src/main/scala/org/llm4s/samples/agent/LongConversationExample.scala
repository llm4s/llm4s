package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, ContextWindowConfig, PruningStrategy }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.toolapi.tools.WeatherTool

/**
 * Example demonstrating long multi-turn conversations with automatic context pruning.
 *
 * Shows how to use `runMultiTurn` and `ContextWindowConfig` to manage
 * conversation history automatically.
 */
object LongConversationExample {

  def main(args: Array[String]): Unit = {
    println("=== Long Conversation with Context Pruning Example ===\n")

    // Configure context window management
    val contextConfig = ContextWindowConfig(
      maxMessages = Some(15),                       // Keep max 15 messages
      preserveSystemMessage = true,                 // Always keep system message
      minRecentTurns = 2,                           // Keep at least 2 recent turns
      pruningStrategy = PruningStrategy.OldestFirst // Drop oldest messages first
    )

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      tools = new ToolRegistry(Seq(WeatherTool.tool))
      agent = new Agent(client)

      // Use runMultiTurn for convenience - automatically chains all turns
      finalState <- agent.runMultiTurn(
        initialQuery = "What's the weather in Paris?",
        followUpQueries = Seq(
          "And in London?",
          "How about Tokyo?",
          "What about New York?",
          "And Sydney?",
          "Which of these cities is the warmest?",
          "Which is the coldest?",
          "Should I pack an umbrella for Paris?",
          "What about sunscreen for Sydney?"
        ),
        tools = tools,
        contextWindowConfig = Some(contextConfig) // Enable automatic pruning
      )

      _ = println("\n=== Conversation Statistics ===")
      _ = println(s"Total messages: ${finalState.conversation.messageCount}")
      _ = println(s"User messages: ${finalState.conversation.filterByRole(MessageRole.User).length}")
      _ = println(s"Assistant messages: ${finalState.conversation.filterByRole(MessageRole.Assistant).length}")
      _ = println(s"Tool messages: ${finalState.conversation.filterByRole(MessageRole.Tool).length}")

      _ = println("\n=== Final Assistant Response ===")
      _ = finalState.conversation.messages
        .filter(_.role == MessageRole.Assistant)
        .lastOption
        .foreach(msg => println(msg.content))

    } yield finalState

    result.fold(
      error => println(s"\nError: ${error.formatted}"),
      state => println(s"\nSuccess! Conversation completed with status: ${state.status}")
    )
  }
}
