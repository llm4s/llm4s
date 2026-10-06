package org.llm4s.samples.basic

import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model._
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.slf4j.LoggerFactory

object BasicLLMCallingWithTrace {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    val result = for {
      tracingSettings <- Llm4sConfig.tracing()
      tracer = Tracing.create(tracingSettings)
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)
      _ = {
        // Create a conversation with messages
        val conversation = Conversation(
          Seq(
            SystemMessage("You are a helpful assistant. You will talk like a pirate."),
            UserMessage("Please write a scala function to add two integers"),
            AssistantMessage("Of course, me hearty! What can I do for ye?"),
            UserMessage("What's the best way to train a parrot?")
          )
        )

        // Trace the start of the agent run
        tracer.traceEvent("Starting LLM conversation")

        // Complete the conversation
        client.complete(conversation) match {
          case Right(completion) =>
            logger.info("Completion ID={} from {} created at {}", completion.id, completion.model, completion.created)
            logger.info("Chat Role: {}", completion.message.role)
            logger.info("Message:")
            logger.info("{}", completion.message.content)

            // Trace the completion with token usage
            tracer.traceCompletion(completion, completion.model)

            // Trace the agent state after completion
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

            // Trace tool calls if present
            completion.message.toolCalls.foreach { tc =>
              tracer.traceToolCall(tc.name, tc.arguments.render(), "(tool output not available in this example)")
            }

          case Left(error) =>
            tracer.traceError(new RuntimeException(error.formatted))
            logger.error("Error: {}", error.formatted)
        }
        tracer.traceEvent("LLM conversation finished")
      }
    } yield ()
    result.fold(err => logger.error("Error: {}", err.formatted), identity)
  }
}
