package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Math Specialist Handoff Example
 *
 * Demonstrates how a general agent can hand off mathematical queries
 * to a specialist agent with expertise in mathematics.
 */
object MathSpecialistHandoffExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=" * 80)
  logger.info("Math Specialist Handoff Example")
  logger.info("=" * 80)

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)

    // Math specialist with its own system prompt
    mathAgent = Agent
      .builder("math", client)
      .withSystemPrompt("You are a mathematics specialist. Solve the problem step by step.")

    // General agent: hands off to the math specialist
    generalAgent <- Agent
      .builder("general", client)
      .withSystemPrompt(
        """You are a general assistant.
          |For mathematical questions involving calculus, algebra, or advanced math,
          |you MUST hand off to the math specialist.
          |Do not attempt to solve advanced math problems yourself.""".stripMargin
      )
      .withHandoffs(
        Handoff.to(
          "math",
          mathAgent,
          "Mathematical questions requiring calculus or advanced math"
        )
      )
      .build()

    // Run general agent with math handoff
    _ = logger.info("Query: 'What is the integral of 2x + 5 from 0 to 10?'")
    _ = logger.info("General agent analyzing query...")

    finalState <- generalAgent.run("What is the integral of 2x + 5 from 0 to 10?")
  } yield finalState

  result match {
    case Right(finalState) =>
      logger.info("=" * 80)
      logger.info("Math question answered")
      logger.info("=" * 80)
      logger.info("Status: {}", finalState.status)
      logger.info("Active agent: {}", finalState.activeAgent.value)
      logger.info("Answer:")
      logger.info("{}", AgentResults.answerOrStatus(finalState))

      // Check if handoff occurred
      if (finalState.activeAgent.value != "general") {
        logger.info("Handoff occurred: general -> {}", finalState.activeAgent.value)
      }

    case Left(error) =>
      logger.error("=" * 80)
      logger.error("Error occurred")
      logger.error("=" * 80)
      logger.error("Error: {}", error.formatted)
  }
}
