package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Example demonstrating basic input validation with guardrails.
 *
 * This example shows how to use built-in guardrails to validate user input
 * before processing it with an agent.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object BasicInputValidationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Basic Input Validation Example ===")

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // Define input guardrails
    inputGuardrails = Seq(
      new LengthCheck(min = 1, max = 10000),
      new ProfanityFilter()
    )

    // Run agent with input validation
    state <- agent.run(
      query = "What is Scala and why is it useful for functional programming?",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = inputGuardrails
    )
  } yield state

  result match {
    case Right(state) =>
      logger.info("✓ Input validation passed!")
      logger.info("Agent response:")
      state.conversation.messages.last.content.split("\n").foreach(line => logger.info("  {}", line))

    case Left(error) =>
      logger.error("✗ Validation or execution failed:")
      logger.error("  Error: {}", error.formatted)
  }

  logger.info("=" * 50)
}
