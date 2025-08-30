package org.llm4s.samples.toolapi

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ LLM, LLMClient }
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool

import scala.annotation.tailrec

/**
 * Example demonstrating how to use the weather tool with an LLM
 */
object LLMWeatherExample {
  def main(args: Array[String]): Unit = {
    // Get LLM client using environment variables
    val client    = LLM.client(LLMConfig())

    // Create a tool registry with the weather tool
    val toolRegistry = new ToolRegistry(Seq(WeatherTool.tool))

    // Create initial conversation with user question
    val initialConversation = Conversation(Seq(UserMessage("What's the weather like in Paris, France?")))

    // Create completion options with the weather tool
    val options = CompletionOptions(
      tools = Seq(WeatherTool.tool)
    )

    println("Sending request to LLM with weather tool...")

    // Make the API request
    processLLMRequest(client, initialConversation, options, toolRegistry)
  }

  /**
   * Process an LLM request, handling any tool calls that might be returned
   */
  @tailrec
  private def processLLMRequest(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions,
    toolRegistry: ToolRegistry
  ): Unit =
    client.complete(conversation, options) match {
      case Right(completion) =>
        val assistantMessage = completion.message

        println("\nLLM Response:")
        println(assistantMessage.content)

        // Check if there are tool calls
        if (assistantMessage.toolCalls.nonEmpty) {
          println("\nTool calls detected, processing...")

          // Process each tool call and create tool messages
          val toolMessages: Seq[ToolMessage] = processToolCalls(assistantMessage.toolCalls, toolRegistry)

          // Create updated conversation with assistant message and tool responses
          val updatedConversation = conversation
            .addMessage(assistantMessage)
            .addMessages(toolMessages)

          println("\nSending follow-up request with tool results...")

          // Make the follow-up API request (without tools this time)
          processLLMRequest(client, updatedConversation, CompletionOptions(), toolRegistry)
        } else {
          // If no tool calls, we're done
          println("\nFinal response (no tool calls needed).")

          // Print token usage if available
          completion.usage.foreach { usage =>
            println(
              s"\nTokens used: ${usage.totalTokens} (${usage.promptTokens} prompt, ${usage.completionTokens} completion)"
            )
          }
        }

      case Left(error) =>
        println(s"Error: ${error.formatted}")
    }

  /**
   * Process tool calls and return tool messages with the results
   */
  private def processToolCalls(toolCalls: Seq[ToolCall], toolRegistry: ToolRegistry): Seq[ToolMessage] =
    toolCalls.map { toolCall =>
      println(s"\nExecuting tool: ${toolCall.name}")
      println(s"Arguments: ${toolCall.arguments}")

      val request    = ToolCallRequest(toolCall.name, toolCall.arguments)
      val toolResult = toolRegistry.execute(request)

      val resultContent = toolResult match {
        case Right(json) => json.render()
        case Left(error) => s"""{ "isError": true, "message": "$error" }"""
      }

      println(s"Tool execution result: $resultContent")

      // Create a tool message with the result
      ToolMessage(toolCall.id, resultContent)
    }
}
