package org.llm4s.samples.basic

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLM
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
    // Print model information
    val model = sys.env.getOrElse("LLM_MODEL", "unknown")
    println(s"=== Streaming Example ===")
    println(s"Using model: $model")
    println("=" * 40)
    
    // Create a conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant who explains things clearly and concisely."),
        UserMessage("Explain the concept of recursion in programming with a simple example.")
      )
    )

    // Get a client using environment variables
    val client    = LLM.client(LLMConfig())

    println("=== Streaming Response ===")
    println("Receiving chunks as they arrive:")
    println("-" * 40)

    var fullContent = ""
    var chunkCount = 0
    val startTime = System.currentTimeMillis()

    // Stream the completion with a callback for each chunk
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
        chunk.toolCall.foreach { toolCall =>
          println(s"\n[Tool Call: ${toolCall.name}]")
        }
        
        // Check if streaming is finished
        chunk.finishReason.foreach { reason =>
          println(s"\n\n[Stream finished: $reason]")
        }
      }
    ) match {
      case Right(completion) =>
        val endTime = System.currentTimeMillis()
        val duration = endTime - startTime
        
        println("\n" + "-" * 40)
        println(s"\n=== Streaming Complete ===")
        println(s"Total chunks received: $chunkCount")
        println(s"Total time: ${duration}ms")
        println(s"Completion ID: ${completion.id}")
        
        // Print token usage if available
        completion.usage.foreach { usage =>
          println(s"Tokens used: ${usage.totalTokens} (${usage.promptTokens} prompt, ${usage.completionTokens} completion)")
        }
        
        // Verify the full content matches the completion message
        if (completion.message.content == fullContent) {
          println("✓ Streamed content matches final completion")
        } else {
          println("⚠ Warning: Streamed content differs from final completion")
        }

      case Left(error) =>
        println(s"\n\nError during streaming: ${error.message}")
        error match {
          case _: org.llm4s.error.AuthenticationError =>
            println("Please check your API key configuration.")
          case _: org.llm4s.error.RateLimitError =>
            println("You've hit the rate limit. Please wait and try again.")
          case _ =>
            println(s"Error details: $error")
        }
    }
    
    println("\n=== Comparison with Non-Streaming ===")
    println("Now calling the same request without streaming...")
    
    val nonStreamStartTime = System.currentTimeMillis()
    client.complete(conversation) match {
      case Right(completion) =>
        val nonStreamEndTime = System.currentTimeMillis()
        val nonStreamDuration = nonStreamEndTime - nonStreamStartTime
        
        println(s"Non-streaming time: ${nonStreamDuration}ms")
        println(s"Response length: ${completion.message.content.length} characters")
        
        // The non-streaming response should be similar
        println("\nNon-streaming response (first 200 chars):")
        println(completion.message.content.take(200) + "...")
        
      case Left(error) =>
        println(s"Error in non-streaming call: ${error.message}")
    }
  }
}
