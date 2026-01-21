package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails._
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Example demonstrating composite guardrails with different validation modes.
 *
 * This example shows how to combine multiple guardrails using different
 * composition strategies (All, Any, Sequential) to create complex validation logic.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object CompositeGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Composite Guardrail Example ===")

  // Example 1: All guardrails must pass (AND logic)
  logger.info("Example 1: All guardrails must pass")

  val safetyChecks = CompositeGuardrail.all(
    Seq(
      new LengthCheck(min = 1, max = 10000),
      new ProfanityFilter()
    )
  )

  val result1 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(safetyChecks.asInstanceOf[InputGuardrail])
    )
  } yield state

  result1 match {
    case Right(_) =>
      logger.info("✓ All safety checks passed!")
      logger.info("  Length check: PASS")
      logger.info("  Profanity filter: PASS")

    case Left(error) =>
      logger.error("✗ Validation failed: {}", error.formatted)
  }

  // Example 2: At least one guardrail must pass (OR logic)
  logger.info("Example 2: At least one guardrail must pass (language detection)")

  val languageDetection = CompositeGuardrail.any(
    Seq(
      new RegexValidator(".*\\b(scala|functional)\\b.*".r),
      new RegexValidator(".*\\b(java|object-oriented)\\b.*".r),
      new RegexValidator(".*\\b(python|dynamic)\\b.*".r)
    )
  )

  val result2 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(languageDetection.asInstanceOf[InputGuardrail])
    )
  } yield state

  result2 match {
    case Right(_) =>
      logger.info("✓ Query matched at least one language pattern!")
      logger.info("  (Contains: scala, functional, java, python, etc.)")

    case Left(error) =>
      logger.error("✗ No language patterns matched: {}", error.formatted)
  }

  // Example 3: Sequential validation (short-circuit on failure)
  logger.info("Example 3: Sequential validation with early termination")

  val sequentialChecks = CompositeGuardrail.sequential(
    Seq(
      new LengthCheck(min = 1, max = 10000), // Check this first (cheap)
      new ProfanityFilter()                  // Only check if length passes
    )
  )

  val result3 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "What is functional programming?",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(sequentialChecks.asInstanceOf[InputGuardrail])
    )
  } yield state

  result3 match {
    case Right(_) =>
      logger.info("✓ All sequential checks passed!")
      logger.info("  Step 1 (Length): PASS")
      logger.info("  Step 2 (Profanity): PASS")

    case Left(error) =>
      logger.error("✗ Validation failed at some step: {}", error.formatted)
  }

  // Example 4: Combining safety and business logic
  logger.info("Example 4: Combining safety and business logic")

  val combinedValidation: InputGuardrail = new InputGuardrail {
    val safetyLayer = CompositeGuardrail.all(
      Seq(
        new LengthCheck(1, 10000),
        new ProfanityFilter()
      )
    )

    val businessLayer = CompositeGuardrail.any(
      Seq(
        new RegexValidator(".*\\b(scala|java|python|rust)\\b.*".r)
      )
    )

    def validate(value: String): org.llm4s.types.Result[String] =
      for {
        safeInput  <- safetyLayer.validate(value)
        validInput <- businessLayer.validate(safeInput)
      } yield validInput

    val name                 = "CombinedValidation"
    override val description = Some("Safety checks + business logic")
  }

  val result4 = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    state <- agent.run(
      query = "Tell me about Scala programming best practices",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(combinedValidation)
    )
  } yield state

  result4 match {
    case Right(state) =>
      logger.info("✓ Combined validation passed!")
      logger.info("  Safety layer: PASS")
      logger.info("  Business layer: PASS")
      logger.info("Response preview:")
      state.conversation.messages.last.content.split("\n").take(3).foreach(line => logger.info("  {}", line))

    case Left(error) =>
      logger.error("✗ Combined validation failed: {}", error.formatted)
  }

  logger.info("=" * 50)
}
