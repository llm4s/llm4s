package org.llm4s.samples.guardrails

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
import org.slf4j.LoggerFactory

/**
 * Example demonstrating basic input validation with guardrails.
 *
 * This example shows how to use built-in guardrails to validate user input
 * before processing it with an agent.
 *
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
 */
object BasicInputValidationExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Basic Input Validation Example ===")

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    // Define input guardrails
    inputGuardrails = Seq(
      new LengthCheck(min = 1, max = 10000),
      new ProfanityFilter()
    )

    // Run agent with input validation
    agent <- Agent
      .builder("basic-input-validation", client)
      .withMiddleware(GuardrailMiddleware(inputGuardrails, Seq.empty))
      .build()
    state <- agent.run("What is Scala and why is it useful for functional programming?")
  } yield state

  result match {
    case Right(state) =>
      state.status match {
        case AgentStatus.Completed(answer) =>
          logger.info("✓ Input validation passed!")
          logger.info("Agent response:")
          answer.split("\n").foreach(line => logger.info("  {}", line))
        case other =>
          logger.warn("✗ Run did not complete: {}", AgentResults.describe(other))
      }

    case Left(error) =>
      logger.error("✗ Validation or execution failed:")
      logger.error("  Error: {}", error.formatted)
  }

  logger.info("=" * 50)
}
