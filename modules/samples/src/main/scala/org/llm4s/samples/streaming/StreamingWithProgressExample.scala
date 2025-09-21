package org.llm4s.samples.streaming

import org.llm4s.config.ConfigReader.LLMConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator

/**
 * Example showing streaming with progress indicators and accumulation.
 *
 * This example demonstrates:
 * - Progress indicators during streaming
 * - Using StreamingAccumulator to collect the response
 * - Custom callbacks for different streaming events
 *
 * To run: sbt "samples/runMain org.llm4s.samples.streaming.StreamingWithProgressExample"
 */
object StreamingWithProgressExample {
  def main(args: Array[String]): Unit = {
    val config = LLMConfig().fold(err => throw new RuntimeException(err.formatted), identity)
    val model = config.getOrElse("LLM_MODEL", "unknown")
    println("=== LLM4S Streaming with Progress Example ===")
    println(s"Using model: $model")
    println("=" * 50 + "\n")

    // Create a conversation that will generate a longer response
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful coding assistant."),
        UserMessage("""
          |Explain the concept of monads in functional programming.
          |Include:
          |1. What they are
          |2. Why they're useful
          |3. A simple Scala example
          |Please be thorough but clear.
        """.stripMargin.trim)
      )
    )
    var chunkCount   = 0
    var charCount    = 0
    var spinnerIndex = 0
    // Get a client (Result-first)
    val result = for {
      reader <- LLMConfig()
      client <- LLMConnect.getClient(reader)
      accumulator = StreamingAccumulator.create()
      startTime   = System.currentTimeMillis()
      spinner     = Array('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
      _ = {
        println("🚀 Starting stream...")
        println("=" * 60)
      }
      completion <- client.streamComplete(
        conversation,
        options = CompletionOptions(
          temperature = 0.5,
          maxTokens = Some(1000)
        ),
        onChunk = chunk => {
          // Add to accumulator
          accumulator.addChunk(chunk)

          // Update counts
          chunkCount += 1
          chunk.content.foreach(c => charCount += c.length)

          // Print content
          chunk.content.foreach(print)

          // Update progress in terminal title (if supported)
          if (chunkCount % 5 == 0) {
            val elapsed = (System.currentTimeMillis() - startTime) / 1000.0
            print(s"\r${spinner(spinnerIndex)} Streaming... [${chunkCount} chunks, ${charCount} chars, ${elapsed}s]")
            spinnerIndex = (spinnerIndex + 1) % spinner.length

            // Move cursor back to continue printing content
            print("\r" + " " * 70 + "\r")
          }
        }
      )
      _ = {
        println("\n✅ Stream completed successfully!\n")
        val totalTime    = System.currentTimeMillis() - startTime
        val avgChunkTime = if (chunkCount > 0) totalTime.toDouble / chunkCount else 0
        // Show detailed statistics
        println("📊 Streaming Performance:")
        println(s"  Total time:        ${totalTime}ms (${totalTime / 1000.0}s)")
        println(s"  Chunks received:   $chunkCount")
        println(s"  Characters:        $charCount")
        println(s"  Avg chunk time:    ${avgChunkTime.toString.format("%.2f")}ms")
        println(s"  Throughput:        ${(charCount * 1000.0 / totalTime).toString.format("%.1f")} chars/sec")

        // Show accumulated content stats
        val fullContent = accumulator.getCurrentContent
        val wordCount   = fullContent.split("\\s+").length
        val lineCount   = fullContent.split("\n").length

        println(s"\n📝 Content Statistics:")
        println(s"  Words:             $wordCount")
        println(s"  Lines:             $lineCount")
        println(s"  Avg words/line:    ${(wordCount.toDouble / lineCount).toString.format("%.1f")}")

        // Show token usage if available
        completion.usage.foreach { usage =>
          println(s"\n💰 Token Usage:")
          println(s"  Prompt tokens:     ${usage.promptTokens}")
          println(s"  Completion tokens: ${usage.completionTokens}")
          println(s"  Total tokens:      ${usage.totalTokens}")

          val tokensPerSecond = usage.completionTokens * 1000.0 / totalTime
          println(s"  Generation speed:  ${tokensPerSecond.toString.format("%.1f")} tokens/sec")
        }

        // Demonstrate that we have the full content accumulated
        println("\n📋 Full response available in accumulator")
        println(s"   First 100 chars: ${fullContent.take(100)}...")
      }
    } yield ()
    result.fold(err => println(s"Error: ${err.formatted}"), identity)
    println("\n=== Example Complete ===")
  }
}
