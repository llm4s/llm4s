package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Example demonstrating Cohere LLM provider usage with llm4s.
 *
 * This example shows:
 * - Configuring the Cohere provider
 * - Creating a conversation with system, user, and assistant messages
 * - Making a completion request with Cohere's Command-R models
 * - Displaying token usage from the response
 *
 * == Quick Start ==
 *
 * 1. Set your Cohere model:
 *    {{{
 *    export LLM_MODEL=cohere/command-r-plus
 *    }}}
 *
 * 2. Set your Cohere API key:
 *    {{{
 *    export COHERE_API_KEY=your-api-key-here
 *    }}}
 *
 * 3. Run the example:
 *    {{{
 *    sbt "samples/runMain org.llm4s.samples.basic.CohereExample"
 *    }}}
 *
 * == Supported Cohere Models ==
 * - `command-r-plus`: Cohere's most capable model with enhanced reasoning
 * - `command-r`: High-performance model for diverse tasks
 *
 * == Expected Behavior ==
 * The Cohere provider will:
 * - Parse the conversation context (system message + history)
 * - Format it according to Cohere's API requirements (preamble + message + chat_history)
 * - Make an HTTP request to Cohere's v1/chat endpoint
 * - Return a completion with token usage information
 *
 * == Troubleshooting ==
 * If you encounter errors:
 * - Verify COHERE_API_KEY is set correctly
 * - Check that LLM_MODEL is set to a cohere model (e.g., "cohere/command-r-plus")
 * - Ensure you have internet connectivity for API calls
 *
 * For more information, see: https://cohere.com/docs
 */
object CohereExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Create a multi-turn conversation
    val conversation = Conversation(
      Seq(
        // System message: Sets the context for the assistant
        SystemMessage("You are a helpful assistant specializing in Scala programming."),

        // User message: Initial request
        UserMessage("Explain what a type parameter is in Scala."),

        // Assistant message: Previous response in conversation
        AssistantMessage(
          """A type parameter is a placeholder for a concrete type that will be provided when
            |a generic class, trait, or method is instantiated or called. They allow you to
            |write reusable code that works with different types while maintaining type safety.""".stripMargin
        ),

        // Follow-up user message
        UserMessage("Can you give me a practical example of a generic function?")
      )
    )

    // Execute with configuration and error handling
    val result = for {
      // Load provider configuration from environment
      providerCfg <- Llm4sConfig.provider()

      // Build LLM client from config
      client <- LLMConnect.getClient(providerCfg)

      // Make the completion request
      completion <- client.complete(
        conversation,
        CompletionOptions(
          temperature = 0.7,
          topP = 0.9
        )
      )

      _ = {
        // Display the response
        logger.info("✓ Success! Response from {}", completion.model)
        logger.info("Response ID: {}", completion.id)
        logger.info("--- Cohere Response ---")
        logger.info("{}", completion.message.content)
        logger.info("--- End Response ---")

        // Display token usage
        completion.usage.foreach { usage =>
          logger.info(
            "Token usage: {} total ({} prompt + {} completion)",
            usage.totalTokens,
            usage.promptTokens,
            usage.completionTokens
          )
        }
      }
    } yield ()

    // Handle errors
    result.fold(
      err => {
        logger.error("✗ Error: {}", err.message)
        System.exit(1)
      },
      _ => {
        logger.info("Example completed successfully")
        System.exit(0)
      }
    )
  }
}
