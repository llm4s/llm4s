package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.InputGuardrail
import org.llm4s.config.Llm4sConfig
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * Custom guardrail that checks for specific keywords.
 *
 * This guardrail ensures that user queries contain required keywords,
 * which can be useful for topic-specific agents or filtering.
 */
class KeywordRequirementGuardrail(requiredKeywords: Set[String]) extends InputGuardrail {
  def validate(value: String): Result[String] = {
    val lowerValue      = value.toLowerCase
    val missingKeywords = requiredKeywords.filterNot(kw => lowerValue.contains(kw.toLowerCase))

    if (missingKeywords.isEmpty) {
      Right(value)
    } else {
      Left(
        ValidationError.invalid(
          "input",
          s"Query must contain keywords: ${missingKeywords.mkString(", ")}"
        )
      )
    }
  }

  val name                 = "KeywordRequirementGuardrail"
  override val description = Some(s"Requires keywords: ${requiredKeywords.mkString(", ")}")
}

/**
 * Example demonstrating custom guardrail implementation.
 *
 * This example shows how to create and use custom guardrails
 * to enforce application-specific validation rules.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object CustomGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== Custom Guardrail Example ===")

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // Create custom guardrail
    customGuardrail = new KeywordRequirementGuardrail(Set("scala", "programming"))

    // Run with custom guardrail
    state <- agent.run(
      query = "Tell me about Scala programming language features",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(customGuardrail)
    )
  } yield state

  result match {
    case Right(state) =>
      logger.info("✓ Query contained required keywords (scala, programming)")
      logger.info("Agent response:")
      state.conversation.messages.last.content.split("\n").take(5).foreach(line => logger.info("  {}", line))

    case Left(error) =>
      logger.error("✗ Validation failed:")
      logger.error("  Error: {}", error.formatted)
  }

  logger.info("=" * 50)

  // Demonstrate failure case
  logger.info("=== Testing with missing keywords ===")

  val failureResult = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent           = new Agent(client)
    customGuardrail = new KeywordRequirementGuardrail(Set("scala", "programming"))

    // This should fail - doesn't contain required keywords
    state <- agent.run(
      query = "What's the weather like today?",
      tools = new ToolRegistry(Seq.empty),
      inputGuardrails = Seq(customGuardrail)
    )
  } yield state

  failureResult match {
    case Right(_) =>
      logger.warn("Unexpected success")

    case Left(error) =>
      logger.info("✓ Expected validation failure:")
      logger.info("  Error: {}", error.formatted)
  }

  logger.info("=" * 50)
}
