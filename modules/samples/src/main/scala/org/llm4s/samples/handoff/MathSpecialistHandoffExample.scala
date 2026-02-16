package org.llm4s.samples.handoff

import org.llm4s.agent.{ Agent, Handoff }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
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
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)

    // General agent
    generalAgent = new Agent(client)

    // Math specialist with specialized system message
    mathAgent = new Agent(client)

    // Run general agent with math handoff
    _ = logger.info("Query: 'What is the integral of 2x + 5 from 0 to 10?'")
    _ = logger.info("General agent analyzing query...")

    finalState <- generalAgent.run(
      query = "What is the integral of 2x + 5 from 0 to 10?",
      tools = ToolRegistry.empty,
      handoffs = Seq(
        Handoff.to(
          mathAgent,
          "Mathematical questions requiring calculus or advanced math"
        )
      ),
      systemPromptAddition = Some(
        """You are a general assistant.
          |For mathematical questions involving calculus, algebra, or advanced math,
          |you MUST hand off to the math specialist.
          |Do not attempt to solve advanced math problems yourself.""".stripMargin
      )
    )
  } yield finalState

  result match {
    case Right(finalState) =>
      logger.info("=" * 80)
      logger.info("Math question answered")
      logger.info("=" * 80)
      logger.info("Status: {}", finalState.status)
      logger.info("Answer:")
      logger.info("{}", finalState.conversation.messages.last.content)

      // Check if handoff occurred
      if (finalState.logs.exists(_.contains("handoff"))) {
        logger.info("Handoff occurred:")
        finalState.logs.filter(_.contains("handoff")).foreach(log => logger.info("  {}", log))
      }

    case Left(error) =>
      logger.error("=" * 80)
      logger.error("Error occurred")
      logger.error("=" * 80)
      logger.error("Error: {}", error.formatted)
  }
}
