package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Example demonstrating multi-turn conversations with tone validation.
 *
 * This example shows how to apply consistent guardrails across
 * multiple turns of a conversation, including tone validation
 * to ensure professional communication.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object MultiTurnToneValidationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Multi-Turn Conversation with Tone Validation ===")

  // Define guardrails to use across all turns
  val inputGuardrails = Seq(
    new LengthCheck(min = 1, max = 5000),
    new ProfanityFilter()
  )

  val outputGuardrails = Seq(
    new ToneValidator(allowedTones = Set(Tone.Professional, Tone.Friendly))
  )

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // Turn 1: Ask about Scala
    _ = logger.info("Turn 1: Asking about Scala...")
    state1 <- agent.run(
      "What is Scala?",
      new ToolRegistry(Seq.empty),
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
    _ = logger.info("  ✓ Response passed tone validation")

    // Turn 2: Ask for details
    _ = logger.info("Turn 2: Asking for main features...")
    state2 <- agent.continueConversation(
      state1,
      "What are its main features?",
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
    _ = logger.info("  ✓ Response passed tone validation")

    // Turn 3: Ask for examples
    _ = logger.info("Turn 3: Asking for code example...")
    state3 <- agent.continueConversation(
      state2,
      "Can you give me a code example?",
      inputGuardrails = inputGuardrails,
      outputGuardrails = outputGuardrails
    )
    _ = logger.info("  ✓ Response passed tone validation")

  } yield state3

  result match {
    case Right(finalState) =>
      logger.info("✓ All turns completed successfully!")
      logger.info("Final conversation stats:")
      logger.info("  Status: {}", finalState.status)
      logger.info("  Total messages: {}", finalState.conversation.messages.length)
      logger.info("  Turns completed: 3")

      logger.info("Final response:")
      finalState.conversation.messages.last.content.split("\n").take(5).foreach(line => logger.info("  {}", line))

    case Left(error) =>
      logger.error("✗ Validation or execution failed:")
      logger.error("  Error: {}", error.formatted)
      logger.info("This could mean:")
      logger.info("  - Input validation failed (profanity or length)")
      logger.info("  - Output tone was not professional or friendly")
      logger.info("  - Agent execution error")
  }

  logger.info("=" * 50)
}
