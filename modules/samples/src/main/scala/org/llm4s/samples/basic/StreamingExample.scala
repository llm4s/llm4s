package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.slf4j.LoggerFactory

/**
 * Demonstrates streaming responses from LLMs using the streamComplete method.
 * Shows how to process chunks as they arrive and compare with non-streaming calls.
 *
 * To run this example:
 * ```bash
 * # Default: the `ollama-local` section of modules/samples/src/main/resources/application.conf.
 * # For OpenAI, add an `openai-main` section (provider = "openai" and a model) to
 * # application.local.conf (docs/getting-started/configuration.md#running-the-samples), then:
 * export OPENAI_API_KEY=sk-...
 * export LLM4S_PROVIDER=openai-main
 *
 * # Run the example
 * sbt "samples/runMain org.llm4s.samples.basic.StreamingExample"
 * ```
 */
object StreamingExample {
  private val logger = LoggerFactory.getLogger(getClass)

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
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      _ = {
        logger.info("=== Streaming Response ===")
        logger.info("Receiving chunks as they arrive:")
        logger.info("-" * 40)
      }
      completion <-
        client.streamComplete(
          conversation,
          CompletionOptions(),
          onChunk = { chunk =>
            chunkCount += 1

            // Process content chunks
            chunk.content.foreach { content =>
              print(content) // Print each chunk as it arrives (Keep print for Streaming UI)
              fullContent += content
            }

            // Handle tool calls if present
            chunk.toolCall.foreach(toolCall => logger.info("[Tool Call: {}]", toolCall.name))

            // Check if streaming is finished
            chunk.finishReason.foreach { reason =>
              // Ensure we break the line after streaming finishes
              println()
              logger.info("[Stream finished: {}]", reason)
            }
          }
        )
      _ = {
        val endTime  = System.currentTimeMillis()
        val duration = endTime - startTime

        logger.info("-" * 40)
        logger.info("=== Streaming Complete ===")
        logger.info("Total chunks received: {}", chunkCount)
        logger.info("Total time: {}ms", duration)
        logger.info("Completion ID: {}", completion.id)

        // Print token usage if available
        completion.usage.foreach { usage =>
          logger.info(
            "Tokens used: {} ({} prompt, {} completion)",
            usage.totalTokens,
            usage.promptTokens,
            usage.completionTokens
          )
        }

        // Verify the full content matches the completion message
        if (completion.message.content == fullContent) {
          logger.info("Streamed content matches final completion")
        } else {
          logger.warn("Warning: Streamed content differs from final completion")
        }
        logger.info("=== Comparison with Non-Streaming ===")
        logger.info("Now calling the same request without streaming...")
      }
      nonStreamStartTime = System.currentTimeMillis()
      noStreamCompletion <- client.complete(conversation)
      _ = {
        val nonStreamEndTime  = System.currentTimeMillis()
        val nonStreamDuration = nonStreamEndTime - nonStreamStartTime

        logger.info("Non-streaming time: {}ms", nonStreamDuration)
        logger.info("Response length: {} characters", noStreamCompletion.message.content.length)

        // The non-streaming response should be similar
        logger.info("Non-streaming response (first 200 chars):")
        logger.info("{}...", noStreamCompletion.message.content.take(200))
      }
    } yield ()

    result.fold(err => logger.error("Error: {}", err.formatted), identity)

  }
}
