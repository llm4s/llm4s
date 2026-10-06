package org.llm4s.samples.guardrails

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Example demonstrating multi-turn conversations with tone validation.
 *
 * This example shows how to apply consistent guardrails across
 * multiple turns of a conversation, including tone validation
 * to ensure professional communication.
 *
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
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
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    agent <- Agent
      .builder("multi-turn-tone-validation", client)
      .withMiddleware(GuardrailMiddleware(inputGuardrails, outputGuardrails))
      .build()

    // Turn 1: Ask about Scala
    _ = logger.info("Turn 1: Asking about Scala...")
    state1 <- agent.run("What is Scala?")
    _ = logger.info("  Turn 1 status: {}", state1.status)

    // Turn 2: Ask for details
    _ = logger.info("Turn 2: Asking for main features...")
    state2 <- agent.continueConversation(state1, "What are its main features?")
    _ = logger.info("  Turn 2 status: {}", state2.status)

    // Turn 3: Ask for examples
    _ = logger.info("Turn 3: Asking for code example...")
    state3 <- agent.continueConversation(state2, "Can you give me a code example?")
    _ = logger.info("  Turn 3 status: {}", state3.status)

  } yield state3

  result match {
    case Right(finalState) =>
      logger.info("Final conversation stats:")
      logger.info("  Status: {}", finalState.status)
      logger.info("  Total messages: {}", finalState.messages.length)

      finalState.status match {
        case AgentStatus.Completed(answer) =>
          logger.info("✓ All turns completed successfully!")
          logger.info("Final response:")
          answer.split("\n").take(5).foreach(line => logger.info("  {}", line))
        case other =>
          logger.warn("✗ The conversation did not complete: {}", AgentResults.describe(other))
      }

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
