package org.llm4s.samples.guardrails

import org.llm4s.agent.Agent
import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi.ToolRegistry
import org.slf4j.LoggerFactory

/**
 * Example demonstrating LLM-as-Judge guardrails.
 *
 * This example shows how to use LLM-based guardrails that leverage a language
 * model to evaluate outputs against natural language criteria. This enables
 * validation of subjective qualities like tone, factual accuracy, and safety.
 *
 * LLM-as-Judge guardrails are useful when:
 * - Validation criteria are subjective or nuanced
 * - Keyword-based validation is insufficient
 * - You need to verify factual accuracy against source material
 * - Quality assessment requires understanding context
 *
 * Note: LLM guardrails have higher latency due to additional API calls.
 * Use them judiciously, after simpler function-based guardrails.
 *
 * Requires: LLM_MODEL and appropriate API key in environment
 */
object LLMJudgeGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  logger.info("=== LLM-as-Judge Guardrail Example ===")

  val result = for {
    providerCfg <- Llm4sConfig.provider()
    client      <- LLMConnect.getClient(providerCfg)
    agent = new Agent(client)

    // === Example 1: Professional Tone Validation ===
    _ = logger.info("1. Testing Professional Tone Guardrail")
    _ = logger.info("-" * 40)

    // LLM evaluates if the response maintains a professional tone
    toneGuardrail = LLMToneGuardrail.professional(client, threshold = 0.7)

    state1 <- agent.run(
      query = "Write a brief professional email response declining a meeting invitation.",
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(toneGuardrail)
    )

    _ = printResult(state1, "Professional Tone Check")

    // === Example 2: Safety Guardrail ===
    _ = logger.info("2. Testing Safety Guardrail")
    _ = logger.info("-" * 40)

    safetyGuardrail = LLMSafetyGuardrail(client, threshold = 0.8)

    state2 <- agent.run(
      query = "Explain how to make a simple paper airplane.",
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(safetyGuardrail)
    )

    _ = printResult(state2, "Safety Check")

    // === Example 3: Quality Assessment ===
    _ = logger.info("3. Testing Response Quality Guardrail")
    _ = logger.info("-" * 40)

    originalQuery    = "What are the benefits of functional programming?"
    qualityGuardrail = LLMQualityGuardrail(client, originalQuery, threshold = 0.7)

    state3 <- agent.run(
      query = originalQuery,
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(qualityGuardrail)
    )

    _ = printResult(state3, "Quality Check")

    // === Example 4: Custom LLM Guardrail ===
    _ = logger.info("4. Testing Custom LLM Guardrail")
    _ = logger.info("-" * 40)

    // Create a custom LLM guardrail for specific criteria
    customGuardrail = LLMGuardrail(
      client = client,
      prompt = "Rate if this response includes practical, actionable advice with specific examples.",
      passThreshold = 0.6,
      guardrailName = "ActionableAdviceGuardrail"
    )

    state4 <- agent.run(
      query = "Give me tips for learning a new programming language.",
      tools = ToolRegistry.empty,
      outputGuardrails = Seq(customGuardrail)
    )

    _ = printResult(state4, "Custom Criteria Check")

    // === Example 5: Combined Function + LLM Guardrails ===
    _ = logger.info("5. Testing Combined Guardrails (Function + LLM)")
    _ = logger.info("-" * 40)

    // Use fast function-based guardrails first, then LLM for nuanced checks
    combinedGuardrails = Seq(
      new LengthCheck(min = 10, max = 5000), // Fast: Check length first
      safetyGuardrail                        // Slow: Then check safety with LLM
    )

    state5 <- agent.run(
      query = "Describe the water cycle in nature.",
      tools = ToolRegistry.empty,
      outputGuardrails = combinedGuardrails
    )

    _ = printResult(state5, "Combined Guardrails Check")

  } yield state5

  result match {
    case Right(_) =>
      logger.info("=" * 50)
      logger.info("✓ All LLM-as-Judge guardrail examples completed successfully!")

    case Left(error) =>
      logger.error("✗ Example failed with error:")
      logger.error("  {}", error.formatted)
  }

  def printResult(state: org.llm4s.agent.AgentState, checkName: String): Unit = {
    val response = state.conversation.messages.last.content
    val preview  = if (response.length > 200) response.take(200) + "..." else response

    logger.info("✓ {} PASSED", checkName)
    logger.info("Response preview: {}", preview)
  }

  logger.info("=" * 50)
}
