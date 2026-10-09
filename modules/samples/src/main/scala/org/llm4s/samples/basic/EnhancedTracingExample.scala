package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.slf4j.LoggerFactory

/**
 * Enhanced tracing example for a simple LLM conversation.
 *
 * Demonstrates:
 * - Creating a tracer from Llm4sConfig
 * - Tracing lifecycle events (start/end)
 * - Tracing completion + token usage
 * - Tracing agent state
 * - Tracing errors
 */
object EnhancedTracingExample {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val result = for {
      // Load tracing settings from config / env
      tracingSettings <- Llm4sConfig.tracing()
      tracer = Tracing.create(tracingSettings)

      // Create LLM client from provider config
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      _ = runConversationWithTracing(client, tracer)
    } yield ()

    result.fold(
      err => logger.error("EnhancedTracingExample failed: {}", err.formatted),
      _ => logger.info("EnhancedTracingExample completed successfully.")
    )
  }

  private def runConversationWithTracing(
    client: org.llm4s.llmconnect.LLMClient,
    tracer: Tracing
  ): Unit = {
    // Build a small multi-turn conversation
    val conversation = Conversation(
      Seq(
        SystemMessage("You are a helpful assistant. Explain things clearly and concisely."),
        UserMessage("Briefly explain what LLM4S is."),
        UserMessage("Then list two typical use cases.")
      )
    )

    // Trace start of conversation
    tracer.traceEvent("EnhancedTracingExample: starting conversation")

    client.complete(conversation) match {
      case Right(completion) =>
        logger.info("Completion model={} id={} created={}", completion.model, completion.id, completion.created)
        logger.info("Assistant reply:\n{}", completion.message.content)

        // Trace completion and token usage
        tracer.traceCompletion(completion, completion.model)

        // Trace a simple agent state snapshot
        val finalMessages = conversation.messages :+ completion.message
        tracer.traceEvent(
          TraceEvent.AgentRunEnded(
            threadId = "sample-thread",
            runId = java.util.UUID.randomUUID().toString,
            agent = "sample-agent",
            status = "completed",
            messages = finalMessages,
            usage = UsageSummary()
          )
        )

      case Left(error) =>
        // Trace error and log it
        tracer.traceError(new RuntimeException(error.formatted))
        logger.error("LLM error in EnhancedTracingExample: {}", error.formatted)
    }

    // Trace end of conversation
    tracer.traceEvent("EnhancedTracingExample: conversation finished")
  }
}
