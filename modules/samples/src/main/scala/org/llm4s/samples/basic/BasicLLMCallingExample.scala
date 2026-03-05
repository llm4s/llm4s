package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Basic example demonstrating simple LLM API calls using LLM4S.
 *
 * This example shows:
 * - Setting up environment variables for different providers
 * - Creating a multi-turn conversation with system, user, and assistant messages
 * - Making a completion request and handling the response
 * - Displaying token usage information
 *
 * == Quick Start ==
 *
 * 1. Set your provider and model:
 *    {{{
 *    export LLM_MODEL=openai/gpt-4o
 *    }}}
 *
 * 2. Set your API key:
 *    {{{
 *    export OPENAI_API_KEY=sk-...
 *    }}}
 *
 * 3. Run the example:
 *    {{{
 *    sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
 *    }}}
 *
 * Optional:
 *  - LLM_MAX_TOKENS
 *      Overrides the provider's default max token limit.
 *      Must be a positive integer.
 *      If unset or invalid, provider defaults are used.
 *
 * == Expected Output ==
 * The LLM will respond with a function to filter even numbers from a list,
 * likely using the `isEven` function from the conversation history.
 * This demonstrates how conversation context helps the LLM provide coherent,
 * contextually relevant responses across multiple turns.
 *
 * == Supported Providers ==
 * - '''OpenAI''': `LLM_MODEL=openai/gpt-4o`, requires `OPENAI_API_KEY`
 * - '''Anthropic''': `LLM_MODEL=anthropic/claude-3-5-sonnet-latest`, requires `ANTHROPIC_API_KEY`
 * - '''Azure''': `LLM_MODEL=azure/<deployment>`, requires `AZURE_API_KEY` and `AZURE_API_BASE`
 * - '''Ollama''': `LLM_MODEL=ollama/llama2`, no API key needed (local)
 * - '''Cohere''': `LLM_MODEL=cohere/command-r`, requires `COHERE_API_KEY`
 *
 * == Troubleshooting ==
 * If you see configuration errors, this example will guide you through
 * setting the correct environment variables for your chosen provider.
 *
 * For more information, see: https://github.com/llm4s/llm4s#getting-started
 */
object BasicLLMCallingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Create a multi-turn conversation demonstrating different message types
    val conversation = Conversation(
      Seq(
        // System message: Defines the assistant's role and behavior
        // Sets the context for how the assistant should respond
        SystemMessage("You are a helpful programming assistant."),

        // User message: Initial request from the user
        UserMessage("Write a Scala function that checks if a number is even."),

        // Assistant message: Previous response in the conversation
        // Including conversation history helps maintain context across multiple turns
        AssistantMessage(
          """Here's a simple function to check if a number is even:
            |
            |```scala
            |def isEven(n: Int): Boolean = n % 2 == 0
            |```
            |
            |This uses the modulo operator (%) to check if the number is divisible by 2.""".stripMargin
        ),

        // User message: Follow-up question
        // The LLM can reference the previous conversation to provide a relevant answer
        UserMessage("Now write a function that filters a list to keep only even numbers.")
      )
    )

    // Execute the example with explicit configuration and error handling
    val result = for {
      // Load provider configuration (model, base URL, API key, etc.)
      providerCfg <- Llm4sConfig.provider()
      // Build LLM client from typed provider config
      client <- LLMConnect.getClient(providerCfg)

      // Make the completion request
      completion <- client.complete(conversation)
      _ = {
        // Display the response
        logger.info("Success! Response from {}", completion.model)
        logger.info("Model ID: {}", completion.id)
        logger.info("Created at: {}", completion.created)
        logger.info("Chat Role: {}", completion.message.role)
        logger.info("--- Response ---")
        logger.info("{}", completion.message.content)
        logger.info("--- End Response ---")

        // Print usage information if available
        completion.usage.foreach { usage =>
          logger.info(
            "Tokens used: {} ({} prompt + {} completion)",
            usage.totalTokens,
            usage.promptTokens,
            usage.completionTokens
          )
        }
      }
    } yield ()

    // Handle errors with helpful guidance
    result.fold(
      err => {
        logger.error("{}", err.formatted)
        logger.info("Tip: Make sure your environment variables or application.conf values are set correctly.")
        logger.info("For more help, see: https://github.com/llm4s/llm4s#getting-started")
      },
      identity
    )
  }
}
