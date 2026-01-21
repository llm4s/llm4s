package org.llm4s.samples.streaming

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Basic example of using the streaming API to get real-time responses from LLMs.
 *
 * To run this example:
 * 1. Set your LLM_MODEL environment variable (e.g., "openai/gpt-4", "anthropic/claude-3-sonnet")
 * 2. Set your API key (OPENAI_API_KEY or ANTHROPIC_API_KEY)
 * 3. Run: sbt "samples/runMain org.llm4s.samples.streaming.BasicStreamingExample"
 */
object BasicStreamingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Create a conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant who explains things clearly and concisely."),
        UserMessage("Write a short story about a robot learning to paint. Make it about 3 paragraphs.")
      )
    )
    var firstChunkTime: Option[Long] = None
    var chunkCount                   = 0

    // Get a client using typed configuration (Result-first)
    val result = for {
      providerCfg <- Llm4sConfig.provider()
      _ = {
        logger.info("=== LLM4S Basic Streaming Example ===")
        logger.info("Using model: {}", providerCfg.model)
        logger.info("=" * 50)
      }
      client <- LLMConnect.getClient(providerCfg)
      startTime = System.currentTimeMillis()
      completion <- client.streamComplete(
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
          chunk.toolCall.foreach(toolCall => logger.info("[Tool Call: {}]", toolCall.name))

          // Show finish reason
          chunk.finishReason.foreach(reason => logger.info("[Stream finished: {}]", reason))
        }
      )
      _ = {
        val totalTime = System.currentTimeMillis() - startTime
        // Terminate the streaming line
        println()
        logger.info("-" * 50)
        logger.info("Streaming completed successfully!")
        logger.info("Message ID: {}", completion.id)

        // Print timing statistics
        logger.info("Streaming Statistics:")
        logger.info("  - Time to first chunk: {}ms", firstChunkTime.getOrElse(0L))
        logger.info("  - Total streaming time: {}ms", totalTime)
        logger.info("  - Number of chunks: {}", chunkCount)

        // Print usage information if available
        completion.usage.foreach { usage =>
          logger.info("Token Usage:")
          logger.info("  - Prompt tokens: {}", usage.promptTokens)
          logger.info("  - Completion tokens: {}", usage.completionTokens)
          logger.info("  - Total tokens: {}", usage.totalTokens)
        }
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)
    logger.info("=== Example Complete ===")
  }
}
