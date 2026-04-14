package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Basic example demonstrating simple LLM API calls using LLM4S.
 *
 * This example shows:
 * - Resolving the configured default named provider
 * - Creating a multi-turn conversation with system, user, and assistant messages
 * - Making a completion request and handling the response
 * - Displaying token usage information
 *
 * == Quick Start ==
 *
 * 1. Configure a default named provider in `application.local.conf`:
 *    {{{
 *    llm4s {
 *      providers {
 *        provider = "openai-main"
 *
 *        openai-main {
 *          provider = "openai"
 *          model = "gpt-4o"
 *          apiKey = ${?OPENAI_API_KEY}
 *        }
 *      }
 *    }
 *    }}}
 *
 * 2. Run the example:
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
 * == Troubleshooting ==
 * If you see configuration errors, this example will guide you through
 * setting the correct named-provider configuration for your chosen provider.
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
      // Load the configured default named provider
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
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
        logger.info("Tip: Make sure llm4s.providers.provider points at a valid named provider configuration.")
        logger.info("For more help, see: https://github.com/llm4s/llm4s#getting-started")
      },
      identity
    )
  }
}
