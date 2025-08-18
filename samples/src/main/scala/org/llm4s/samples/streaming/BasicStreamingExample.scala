package org.llm4s.samples.streaming

import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._

/**
 * Basic example of using the streaming API to get real-time responses from LLMs.
 * 
 * To run this example:
 * 1. Set your LLM_MODEL environment variable (e.g., "openai/gpt-4", "anthropic/claude-3-sonnet")
 * 2. Set your API key (OPENAI_API_KEY or ANTHROPIC_API_KEY)
 * 3. Run: sbt "samples/runMain org.llm4s.samples.streaming.BasicStreamingExample"
 */
object BasicStreamingExample {
  def main(args: Array[String]): Unit = {
    val model = sys.env.getOrElse("LLM_MODEL", "unknown")
    println("=== LLM4S Basic Streaming Example ===")
    println(s"Using model: $model")
    println("=" * 50 + "\n")
    
    // Create a conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant who explains things clearly and concisely."),
        UserMessage("Write a short story about a robot learning to paint. Make it about 3 paragraphs.")
      )
    )
    
    // Get a client using environment variables
    val client = LLM.client()
    
    println("Streaming response from LLM...\n")
    println("-" * 50)
    
    // Track timing
    val startTime = System.currentTimeMillis()
    var firstChunkTime: Option[Long] = None
    var chunkCount = 0
    
    // Stream the completion with real-time output
    val result = client.streamComplete(
      conversation,
      options = CompletionOptions(temperature = 0.7),
      onChunk = chunk => {
        // Record time to first chunk
        if (firstChunkTime.isEmpty && chunk.content.isDefined) {
          firstChunkTime = Some(System.currentTimeMillis() - startTime)
        }
        
        // Print content as it arrives
        chunk.content.foreach(print)
        
        // Track chunks
        chunkCount += 1
        
        // Show when we receive tool calls (if any)
        chunk.toolCall.foreach { toolCall =>
          println(s"\n[Tool Call: ${toolCall.name}]")
        }
        
        // Show finish reason
        chunk.finishReason.foreach { reason =>
          println(s"\n[Stream finished: $reason]")
        }
      }
    )
    
    println("\n" + "-" * 50)
    
    // Calculate timing
    val totalTime = System.currentTimeMillis() - startTime
    
    // Handle the final result
    result match {
      case Right(completion) =>
        println("\n✅ Streaming completed successfully!")
        println(s"Message ID: ${completion.id}")
        
        // Print timing statistics
        println(s"\n📊 Streaming Statistics:")
        println(s"  - Time to first chunk: ${firstChunkTime.getOrElse(0L)}ms")
        println(s"  - Total streaming time: ${totalTime}ms")
        println(s"  - Number of chunks: $chunkCount")
        
        // Print usage information if available
        completion.usage.foreach { usage =>
          println(s"\n💰 Token Usage:")
          println(s"  - Prompt tokens: ${usage.promptTokens}")
          println(s"  - Completion tokens: ${usage.completionTokens}")
          println(s"  - Total tokens: ${usage.totalTokens}")
        }
        
      case Left(error) =>
        println(s"\n❌ Streaming failed: ${error.message}")
        error match {
          case e: org.llm4s.error.AuthenticationError =>
            println("Please check your API key configuration.")
          case e: org.llm4s.error.RateLimitError =>
            println("You've hit the rate limit. Please wait and try again.")
          case _ =>
            println(s"Error details: $error")
        }
    }
    
    println("\n=== Example Complete ===")
  }
}