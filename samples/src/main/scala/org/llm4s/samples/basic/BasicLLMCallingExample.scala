package org.llm4s.samples.basic

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._

/**
 * Basic example demonstrating simple LLM API calls using LLM4S.
 * Shows how to create a conversation and get a completion response.
 * 
 * To run this example:
 * ```bash
 * # Set up environment variables (choose one provider)
 * export LLM_MODEL=openai/gpt-4o           # For OpenAI
 * export OPENAI_API_KEY=sk-...             # Your OpenAI API key
 * 
 * # OR for Anthropic:
 * export LLM_MODEL=anthropic/claude-3-5-sonnet-latest
 * export ANTHROPIC_API_KEY=sk-ant-...      # Your Anthropic API key
 * 
 * # OR for Azure OpenAI:
 * export LLM_MODEL=azure/<deployment-name>
 * export AZURE_OPENAI_API_KEY=...
 * export AZURE_OPENAI_ENDPOINT=https://<resource>.openai.azure.com/
 * 
 * # Run the example
 * sbt "samples/runMain org.llm4s.samples.basic.BasicLLMCallingExample"
 * ```
 */
object BasicLLMCallingExample {
  def main(args: Array[String]): Unit = {
    // Create a conversation with messages
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant. You will talk like a pirate."),
        UserMessage("Please write a scala function to add two integers"),
        AssistantMessage("Of course, me hearty! What can I do for ye?"),
        UserMessage("What's the best way to train a parrot?")
      )
    )

    // Get a client using environment variables
    val client    = LLM.client(LLMConfig())

    
    // Complete the conversation
    client.complete(conversation) match {
      case Right(completion) =>
        println(s"Model ID=${completion.id} is created at ${completion.created}")
        println(s"Chat Role: ${completion.message.role}")
        println("Message:")
        println(completion.message.content)

        // Print usage information if available
        completion.usage.foreach { usage =>
          println(
            s"Tokens used: ${usage.totalTokens} (${usage.promptTokens} prompt, ${usage.completionTokens} completion)"
          )
        }

      case Left(error) =>
        println(s"Error: ${error.message}")
    }
  }
}
