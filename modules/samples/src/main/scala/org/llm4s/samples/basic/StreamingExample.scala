package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._

/**
 * Demonstrates streaming responses from LLMs using the streamComplete method.
 * Shows how to process chunks as they arrive and compare with non-streaming calls.
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
 * # Run the example
 * sbt "samples/runMain org.llm4s.samples.basic.StreamingExample"
 * ```
 */
object StreamingExample {
  def main(args: Array[String]): Unit = {
    // Create a conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant who explains things clearly and concisely."),
        UserMessage("Explain the concept of recursion in programming with a simple example.")
      )
    )

    var fullContent = ""
    var chunkCount  = 0
    val startTime   = System.currentTimeMillis()
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)
      _ = {
        println("=== Streaming Response ===")
        println("Receiving chunks as they arrive:")
        println("-" * 40)
      }
      completion <-
        client.streamComplete(
          conversation,
          CompletionOptions(),
          onChunk = { chunk =>
            chunkCount += 1

            // Process content chunks
            chunk.content.foreach { content =>
              print(content) // Print each chunk as it arrives
              fullContent += content
            }

            // Handle tool calls if present
            chunk.toolCall.foreach(toolCall => println(s"\n[Tool Call: ${toolCall.name}]"))

            // Check if streaming is finished
            chunk.finishReason.foreach(reason => println(s"\n\n[Stream finished: $reason]"))
          }
        )
      _ = {
        val endTime  = System.currentTimeMillis()
        val duration = endTime - startTime

        println("\n" + "-" * 40)
        println(s"\n=== Streaming Complete ===")
        println(s"Total chunks received: $chunkCount")
        println(s"Total time: ${duration}ms")
        println(s"Completion ID: ${completion.id}")

        // Print token usage if available
        completion.usage.foreach { usage =>
          println(
            s"Tokens used: ${usage.totalTokens} (${usage.promptTokens} prompt, ${usage.completionTokens} completion)"
          )
        }

        // Verify the full content matches the completion message
        if (completion.message.content == fullContent) {
          println("✓ Streamed content matches final completion")
        } else {
          println("⚠ Warning: Streamed content differs from final completion")
        }
        println("\n=== Comparison with Non-Streaming ===")
        println("Now calling the same request without streaming...")
      }
      nonStreamStartTime = System.currentTimeMillis()
      noStreamCompletion <- client.complete(conversation)
      _ = {
        val nonStreamEndTime  = System.currentTimeMillis()
        val nonStreamDuration = nonStreamEndTime - nonStreamStartTime

        println(s"Non-streaming time: ${nonStreamDuration}ms")
        println(s"Response length: ${noStreamCompletion.message.content.length} characters")

        // The non-streaming response should be similar
        println("\nNon-streaming response (first 200 chars):")
        println(noStreamCompletion.message.content.take(200) + "...")
      }
    } yield ()

    result.fold(err => println(s"Error: ${err.formatted}"), identity)

  }
}
