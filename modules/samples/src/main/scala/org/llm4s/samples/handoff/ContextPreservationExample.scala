package org.llm4s.samples.handoff

import org.llm4s.agent.Agent
import org.llm4s.agent.graph.{ RunConfig, ThreadId }
import org.llm4s.llmconnect.model.MessageRole
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Context Preservation Example
 *
 * Demonstrates handing a conversation over to a specialist agent: the general agent's messages
 * (without its system prompt) become the `history` of a run on a new thread, so the specialist
 * sees the full context. For model-initiated handoffs, see `SimpleTriageHandoffExample`.
 */
object ContextPreservationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 80)
  logger.info("Context Preservation (history hand-over) Example")
  logger.info("=" * 80)

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)

    generalAgent <- Agent.builder("general", client).build()
    specialistAgent <- Agent
      .builder("physics", client)
      .withSystemPrompt("You are a quantum physics specialist. Use the conversation so far.")
      .build()

    // Multi-turn conversation with context
    _ = logger.info("Turn 1: 'I'm working on a quantum computing project'")

    state1 <- generalAgent.run("I'm working on a quantum computing project")

    _ = logger.info("Response: {}", AgentResults.answerOrStatus(state1))
    _ = logger.info("Turn 2: 'Can you explain quantum entanglement in detail?'")

    state2 <- generalAgent.continueConversation(state1, "Can you explain quantum entanglement in detail?")

    // Hand the conversation to the specialist on a new thread, with the general agent's
    // messages as its history. System prompts belong to agents and are not imported.
    _ = logger.info("Handing off to specialist with full context as history...")

    history = state2.messages.filterNot(_.role == MessageRole.System)

    finalState <- specialistAgent.run(
      ThreadId(java.util.UUID.randomUUID().toString),
      "Please continue, going deeper on the mathematics.",
      RunConfig(),
      history
    )

  } yield (state2, finalState)

  result match {
    case Right((state2, finalState)) =>
      logger.info("=" * 80)
      logger.info("Context preservation demonstration complete")
      logger.info("=" * 80)
      logger.info("Original conversation messages: {}", state2.messages.length)
      logger.info("Specialist received messages: {}", finalState.messages.length)
      logger.info("Specialist's response:")
      logger.info("{}", AgentResults.answerOrStatus(finalState))

      logger.info("Full conversation flow:")
      state2.messages.zipWithIndex.foreach { case (msg, idx) =>
        val preview = msg.content.take(80) + "..."
        logger.info("  {}. [{}] {}", idx + 1, msg.role, preview)
      }

    case Left(error) =>
      logger.error("=" * 80)
      logger.error("Error occurred")
      logger.error("=" * 80)
      logger.error("Error: {}", error.formatted)
  }
}
