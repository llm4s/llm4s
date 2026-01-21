package org.llm4s.samples.streaming

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.streaming.StreamingAccumulator
import org.slf4j.LoggerFactory

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
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
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
      providerCfg <- Llm4sConfig.provider()
      _ = {
        logger.info("=== LLM4S Streaming with Progress Example ===")
        logger.info("Using model: {}", providerCfg.model)
        logger.info("=" * 50)
      }
      client <- LLMConnect.getClient(providerCfg)
      accumulator = StreamingAccumulator.create()
      startTime   = System.currentTimeMillis()
      spinner     = Array('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
      _ = {
        logger.info("Starting stream...")
        logger.info("=" * 60)
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
            // Spinner is printed directly to stdout for streaming UX,
            // then cleared to avoid interfering with structured logs
            print(s"\r${spinner(spinnerIndex)} Streaming... [${chunkCount} chunks, ${charCount} chars, ${elapsed}s]")
            spinnerIndex = (spinnerIndex + 1) % spinner.length

            // Move cursor back to continue printing content
            print("\r" + " " * 70 + "\r")
          }
        }
      )
      _ = {
        // Ensure we end the streaming line cleanly before logging stats
        logger.info("")
        logger.info("Stream completed successfully!")
        val totalTime    = System.currentTimeMillis() - startTime
        val avgChunkTime = if (chunkCount > 0) totalTime.toDouble / chunkCount else 0
        // Show detailed statistics
        logger.info("Streaming Performance:")
        logger.info("  Total time:        {}ms ({}s)", totalTime, totalTime / 1000.0)
        logger.info("  Chunks received:   {}", chunkCount)
        logger.info("  Characters:        {}", charCount)
        logger.info("  Avg chunk time:    {}ms", avgChunkTime.toString.format("%.2f"))
        logger.info("  Throughput:        {} chars/sec", (charCount * 1000.0 / totalTime).toString.format("%.1f"))

        // Show accumulated content stats
        val fullContent = accumulator.getCurrentContent
        val wordCount   = fullContent.split("\\s+").length
        val lineCount   = fullContent.split("\n").length

        logger.info("Content Statistics:")
        logger.info("  Words:             {}", wordCount)
        logger.info("  Lines:             {}", lineCount)
        logger.info("  Avg words/line:    {}", (wordCount.toDouble / lineCount).toString.format("%.1f"))

        // Show token usage if available
        completion.usage.foreach { usage =>
          logger.info("Token Usage:")
          logger.info("  Prompt tokens:     {}", usage.promptTokens)
          logger.info("  Completion tokens: {}", usage.completionTokens)
          logger.info("  Total tokens:      {}", usage.totalTokens)

          val tokensPerSecond = usage.completionTokens * 1000.0 / totalTime
          logger.info("  Generation speed:  {} tokens/sec", tokensPerSecond.toString.format("%.1f"))
        }

        // Demonstrate that we have the full content accumulated
        logger.info("Full response available in accumulator")
        logger.info("  First 100 chars: {}...", fullContent.take(100))
      }
    } yield ()
    result.fold(err => logger.error("Error: {}", err.formatted), identity)
    logger.info("=== Example Complete ===")
  }
}
