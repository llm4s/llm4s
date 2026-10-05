package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.llmconnect.model.ToolMessage
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Context Preservation Example
 *
 * Demonstrates how conversation context is preserved across handoffs.
 * With `preserveContext = true` the specialist agent receives the full conversation history: the
 * earlier turns, the question, and the handoff request itself.
 */
object ContextPreservationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 80)
  logger.info("Context Preservation Handoff Example")
  logger.info("=" * 80)

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)

    generalAgent    = new Agent(client)
    specialistAgent = new Agent(client)

    handoff = Handoff(
      id = "physics",
      targetAgent = specialistAgent,
      transferReason = Some("Quantum physics expertise required"),
      preserveContext = true, // Transfer full conversation history
      transferSystemMessage = false
    )

    // Multi-turn conversation with context
    _ = logger.info("Turn 1: 'I'm working on a quantum computing project'")

    state1 <- generalAgent.run(
      query = "I'm working on a quantum computing project",
      tools = ToolRegistry.empty
    )

    _ = logger.info("Response: {}", state1.messages.last.content)
    _ = logger.info("Turn 2: 'Can you explain quantum entanglement in detail?'")
    _ = logger.info("(The model can hand this off to the specialist, which then sees the whole conversation)")

    // The handoff is offered as a tool; the model decides whether to call it
    state2 <- generalAgent.continueConversation(
      previous = state1,
      newUserMessage = "Can you explain quantum entanglement in detail?",
      tools = ToolRegistry.empty,
      handoffs = Seq(handoff),
      maxSteps = Some(10)
    )

  } yield (state1, state2)

  result match {
    case Right((state1, state2)) =>
      val handedOff = state2.messages.exists {
        case tool: ToolMessage => tool.content.contains("handoff_requested")
        case _                 => false
      }
      logger.info("=" * 80)
      logger.info("Context preservation demonstration complete")
      logger.info("=" * 80)
      logger.info("Messages before the handoff question: {}", state1.messages.length)
      logger.info("Messages in the final conversation: {}", state2.messages.length)
      logger.info("The question was handed off to the specialist: {}", handedOff)
      logger.info("Final response:")
      logger.info("{}", state2.messages.last.content)

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
