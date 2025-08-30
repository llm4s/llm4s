package org.llm4s.samples.basic

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLM
import org.llm4s.llmconnect.model._

import scala.collection.mutable

/**
 * Advanced streaming example demonstrating real-time progress indicators,
 * detailed metrics tracking, and streaming analytics for LLM responses.
 * 
 * Features:
 * - Visual progress indicators during streaming
 * - Word wrapping for better display
 * - Detailed streaming metrics (chunk delays, throughput, token usage)
 * - Content statistics (words, sentences, paragraphs)
 * - Chunk delay distribution histogram
 * 
 * To run this example:
 * ```bash
 * # Set up environment variables (choose one provider)
 * export LLM_MODEL=openai/gpt-4o-mini      # For OpenAI (faster model)
 * export OPENAI_API_KEY=sk-...             # Your OpenAI API key
 * 
 * # OR for Anthropic:
 * export LLM_MODEL=anthropic/claude-3-5-haiku-latest  # Faster Anthropic model
 * export ANTHROPIC_API_KEY=sk-ant-...      # Your Anthropic API key
 * 
 * # Optional: Enable tracing for additional insights
 * export TRACING_MODE=print                # or "langfuse" with proper keys
 * 
 * # Run the example
 * sbt "samples/runMain org.llm4s.samples.basic.AdvancedStreamingExample"
 * 
 * # For Scala 2.13 compatibility:
 * sbt ++2.13.16 "samples/runMain org.llm4s.samples.basic.AdvancedStreamingExample"
 * ```
 * 
 * Note: This example generates a short story, which works best with models
 * that support longer responses. The streaming metrics are most interesting
 * when the response is longer (3+ paragraphs).
 */
object AdvancedStreamingExample {
  def main(args: Array[String]): Unit = {
    // Print model information
    val model = sys.env.getOrElse("LLM_MODEL", "unknown")
    println(s"🎨 Advanced Streaming Example - Story Generation")
    println(s"📍 Using model: $model")
    println("=" * 60)
    
    // Create a multi-turn conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a creative storyteller. Tell engaging stories with vivid descriptions."),
        UserMessage("Tell me a short story about a robot learning to paint. Make it about 3 paragraphs.")
      )
    )

    val client = LLM.client(LLMConfig())

    println("🎨 Advanced Streaming Example - Story Generation")
    println("=" * 60)
    
    // Track streaming metrics
    var firstChunkTime: Option[Long] = None
    var lastChunkTime: Long = 0
    val chunkSizes = mutable.ArrayBuffer[Int]()
    val chunkTimes = mutable.ArrayBuffer[Long]()
    var wordCount = 0
    var sentenceCount = 0
    var paragraphCount = 0
    val startTime = System.currentTimeMillis()
    
    // Visual progress indicator
    val spinner = Array('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
    var spinnerIndex = 0
    
    println("\n📖 Story streaming in progress...\n")
    
    // Buffer for current line to handle word wrapping
    var currentLine = ""
    val maxLineLength = 80
    
    client.streamComplete(
      conversation,
      CompletionOptions(),
      onChunk = { chunk =>
        val chunkTime = System.currentTimeMillis()
        
        // Record first chunk timing
        if (firstChunkTime.isEmpty) {
          firstChunkTime = Some(chunkTime - startTime)
          println(s"⚡ First chunk received in ${firstChunkTime.get}ms\n")
          println("-" * maxLineLength)
        }
        
        chunk.content.foreach { content =>
          // Track metrics
          chunkSizes += content.length
          if (lastChunkTime > 0) {
            chunkTimes += (chunkTime - lastChunkTime)
          }
          lastChunkTime = chunkTime
          
          // Count words and sentences
          wordCount += content.split("\\s+").filter(_.nonEmpty).length
          sentenceCount += content.count(c => c == '.' || c == '!' || c == '?')
          paragraphCount += content.count(_ == '\n')
          
          // Handle word wrapping for better display
          val words = (currentLine + content).split(" ")
          currentLine = ""
          
          words.foreach { word =>
            if ((currentLine + " " + word).trim.length > maxLineLength) {
              println(currentLine)
              currentLine = word
            } else {
              currentLine = if (currentLine.isEmpty) word else s"$currentLine $word"
            }
          }
          
          // Handle newlines
          if (content.contains("\n")) {
            println(currentLine)
            currentLine = ""
            content.split("\n").tail.foreach { _ => println() }
          }
        }
        
        // Show spinner for visual feedback during pauses
        if (chunk.content.isEmpty && chunk.finishReason.isEmpty) {
          print(s"\r${spinner(spinnerIndex)} Processing...")
          spinnerIndex = (spinnerIndex + 1) % spinner.length
          Thread.sleep(50)
        }
      }
    ) match {
      case Right(completion) =>
        // Print any remaining content
        if (currentLine.nonEmpty) {
          println(currentLine)
        }
        
        val totalTime = System.currentTimeMillis() - startTime
        
        println("\n" + "-" * maxLineLength)
        println("\n📊 Streaming Analytics")
        println("=" * 60)
        
        // Calculate statistics
        val avgChunkSize = if (chunkSizes.nonEmpty) chunkSizes.sum / chunkSizes.length else 0
        val avgChunkDelay = if (chunkTimes.nonEmpty) chunkTimes.sum / chunkTimes.length else 0
        val throughput = if (totalTime > 0) (completion.message.content.length * 1000.0 / totalTime) else 0
        
        println(f"⏱️  Total streaming time: ${totalTime}ms")
        println(f"⚡ Time to first chunk: ${firstChunkTime.getOrElse(0L)}ms")
        println(f"📦 Total chunks received: ${chunkSizes.length}")
        println(f"📏 Average chunk size: $avgChunkSize characters")
        println(f"⏳ Average delay between chunks: ${avgChunkDelay}ms")
        println(f"🚀 Throughput: ${throughput}%.1f characters/second")
        println()
        println(f"📝 Content Statistics:")
        println(f"   - Total characters: ${completion.message.content.length}")
        println(f"   - Words: ~$wordCount")
        println(f"   - Sentences: ~$sentenceCount")
        println(f"   - Paragraphs: ~${paragraphCount + 1}")
        
        // Token usage
        completion.usage.foreach { usage =>
          println()
          println(f"🎯 Token Usage:")
          println(f"   - Prompt tokens: ${usage.promptTokens}")
          println(f"   - Completion tokens: ${usage.completionTokens}")
          println(f"   - Total tokens: ${usage.totalTokens}")
          val tokensPerSecond = if (totalTime > 0) (usage.completionTokens * 1000.0 / totalTime) else 0
          println(f"   - Generation speed: ${tokensPerSecond}%.1f tokens/second")
        }
        
        // Create a histogram of chunk delays
        if (chunkTimes.nonEmpty) {
          println("\n📈 Chunk Delay Distribution (ms):")
          val buckets = Seq(0, 10, 25, 50, 100, 200, 500, 1000)
          buckets.sliding(2).foreach { case Seq(min, max) =>
            val count = chunkTimes.count(t => t >= min && t < max)
            if (count > 0) {
              val bar = "█" * (count * 40 / chunkTimes.length).max(1)
              println(f"   $min%3d-$max%3d ms: $bar $count")
            }
          }
        }
        
        println("\n✅ Streaming completed successfully!")

      case Left(error) =>
        println(s"\n\n❌ Error during streaming: ${error.message}")
        error match {
          case _: org.llm4s.error.AuthenticationError =>
            println("Please check your API key configuration.")
          case _: org.llm4s.error.RateLimitError =>
            println("You've hit the rate limit. Please wait and try again.")
          case _ =>
            println(s"Error type: ${error.getClass.getSimpleName}")
        }
    }
  }
}