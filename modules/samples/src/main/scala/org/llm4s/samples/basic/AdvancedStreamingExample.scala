package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

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
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {

    // Create a multi-turn conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a creative storyteller. Tell engaging stories with vivid descriptions."),
        UserMessage("Tell me a short story about a robot learning to paint. Make it about 3 paragraphs.")
      )
    )
    // Track streaming metrics
    var firstChunkTime: Option[Long] = None
    var lastChunkTime: Long          = 0
    val chunkSizes                   = mutable.ArrayBuffer[Int]()
    val chunkTimes                   = mutable.ArrayBuffer[Long]()
    var wordCount                    = 0
    var sentenceCount                = 0
    var paragraphCount               = 0
    val startTime                    = System.currentTimeMillis()

    // Visual progress indicator
    val spinner      = Array('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
    var spinnerIndex = 0

    // Buffer for current line to handle word wrapping
    var currentLine   = ""
    val maxLineLength = 80

    val result = for {
      providerCfg <- Llm4sConfig.provider()
      _ = {
        val model = providerCfg.model
        logger.info("Advanced Streaming Example - Story Generation")
        logger.info("Using model: {}", model)
        logger.info("=" * 60)
        logger.info("Story streaming in progress...")
      }
      client <- LLMConnect.getClient(providerCfg)
      completion <- {
        // Separator for the story output
        println("=" * 60)
        client.streamComplete(
          conversation,
          CompletionOptions(),
          onChunk = { chunk =>
            val chunkTime = System.currentTimeMillis()
            // Record first chunk timing
            if (firstChunkTime.isEmpty) {
              firstChunkTime = Some(chunkTime - startTime)
              // Print this info to stdout as part of the "story UI" experience
              println(s"First chunk received in ${firstChunkTime.get}ms\n")
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
                content.split("\n").tail.foreach(_ => println())
              }
            }

            // Show spinner for visual feedback during pauses
            if (chunk.content.isEmpty && chunk.finishReason.isEmpty) {
              print(s"\r${spinner(spinnerIndex)} Processing...")
              spinnerIndex = (spinnerIndex + 1) % spinner.length
              Thread.sleep(50)
            }
          }
        )
      }
      _ = {
        if (currentLine.nonEmpty) {
          println(currentLine)
        }

        val totalTime = System.currentTimeMillis() - startTime

        // End of story UI
        println("\n" + "-" * maxLineLength)
        logger.info("Streaming Analytics")
        logger.info("=" * 60)

        // Calculate statistics
        val avgChunkSize  = if (chunkSizes.nonEmpty) chunkSizes.sum / chunkSizes.length else 0
        val avgChunkDelay = if (chunkTimes.nonEmpty) chunkTimes.sum / chunkTimes.length else 0
        val throughput    = if (totalTime > 0) completion.message.content.length * 1000.0 / totalTime else 0

        logger.info("Total streaming time: {}ms", totalTime)
        logger.info("Time to first chunk: {}ms", firstChunkTime.getOrElse(0L))
        logger.info("Total chunks received: {}", chunkSizes.length)
        logger.info("Average chunk size: {} characters", avgChunkSize)
        logger.info("Average delay between chunks: {}ms", avgChunkDelay)
        logger.info("Throughput: {} characters/second", f"$throughput%.1f")
        logger.info("")
        logger.info("Content Statistics:")
        logger.info("  - Total characters: {}", completion.message.content.length)
        logger.info("  - Words: ~{}", wordCount)
        logger.info("  - Sentences: ~{}", sentenceCount)
        logger.info("  - Paragraphs: ~{}", paragraphCount + 1)

        // Token usage
        completion.usage.foreach { usage =>
          logger.info("Token Usage:")
          logger.info("  - Prompt tokens: {}", usage.promptTokens)
          logger.info("  - Completion tokens: {}", usage.completionTokens)
          logger.info("  - Total tokens: {}", usage.totalTokens)
          val tokensPerSecond = if (totalTime > 0) usage.completionTokens * 1000.0 / totalTime else 0
          logger.info("  - Generation speed: {} tokens/second", f"$tokensPerSecond%.1f")
        }

        // Create a histogram of chunk delays
        if (chunkTimes.nonEmpty) {
          logger.info("Chunk Delay Distribution (ms):")
          val buckets = Seq(0, 10, 25, 50, 100, 200, 500, 1000)
          buckets.sliding(2).foreach { case Seq(min, max) =>
            val count = chunkTimes.count(t => t >= min && t < max)
            if (count > 0) {
              val bar = "█" * (count * 40 / chunkTimes.length).max(1)
              logger.info(f"  $min%3d-$max%3d ms: $bar $count")
            }
          }
        }

        logger.info("Streaming completed successfully!")
      }
    } yield ()
    result.fold(error => logger.error("Error: {}", error.message), _ => ())
  }
}
