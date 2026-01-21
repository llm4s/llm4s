package org.llm4s.samples.toolapi

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.{ LLMConnect, LLMClient }
import org.llm4s.toolapi._
import org.llm4s.toolapi.tools.WeatherTool
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

/**
 * Example demonstrating how to use the weather tool with an LLM
 */
object LLMWeatherExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      _ = {
        val toolRegistry        = new ToolRegistry(Seq(WeatherTool.tool))
        val initialConversation = Conversation(Seq(UserMessage("What's the weather like in Paris, France?")))
        val options             = CompletionOptions(tools = Seq(WeatherTool.tool))
        logger.info("Sending request to LLM with weather tool...")
        processLLMRequest(client, initialConversation, options, toolRegistry)
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)
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

        logger.info("LLM Response:")
        logger.info("{}", assistantMessage.content)

        // Check if there are tool calls
        if (assistantMessage.toolCalls.nonEmpty) {
          logger.info("Tool calls detected, processing...")

          // Process each tool call and create tool messages
          val toolMessages: Seq[ToolMessage] = processToolCalls(assistantMessage.toolCalls, toolRegistry)

          // Create updated conversation with assistant message and tool responses
          val updatedConversation = conversation
            .addMessage(assistantMessage)
            .addMessages(toolMessages)

          logger.info("Sending follow-up request with tool results...")

          // Make the follow-up API request (without tools this time)
          processLLMRequest(client, updatedConversation, CompletionOptions(), toolRegistry)
        } else {
          // If no tool calls, we're done
          logger.info("Final response (no tool calls needed).")

          // Print token usage if available
          completion.usage.foreach { usage =>
            logger.info(
              "Tokens used: {} ({} prompt, {} completion)",
              usage.totalTokens,
              usage.promptTokens,
              usage.completionTokens
            )
          }
        }

      case Left(error) =>
        logger.error("Error: {}", error.formatted)
    }

  /**
   * Process tool calls and return tool messages with the results
   */
  private def processToolCalls(toolCalls: Seq[ToolCall], toolRegistry: ToolRegistry): Seq[ToolMessage] =
    toolCalls.map { toolCall =>
      logger.info("Executing tool: {}", toolCall.name)
      logger.info("Arguments: {}", toolCall.arguments)

      val request    = ToolCallRequest(toolCall.name, toolCall.arguments)
      val toolResult = toolRegistry.execute(request)

      val resultContent = toolResult match {
        case Right(json) => json.render()
        case Left(error) => s"""{ "isError": true, "message": "$error" }"""
      }

      logger.info("Tool execution result: {}", resultContent)

      // Create a tool message with the result
      ToolMessage(toolCall.id, resultContent)
    }
}
