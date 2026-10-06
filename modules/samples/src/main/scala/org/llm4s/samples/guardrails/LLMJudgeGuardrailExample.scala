package org.llm4s.samples.guardrails

import org.llm4s.agent.{ Agent, AgentResult, AgentStatus }
import org.llm4s.agent.graph.middleware.GuardrailMiddleware
import org.llm4s.agent.guardrails.LLMGuardrail
import org.llm4s.agent.guardrails.builtin._
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.samples.util.AgentResults
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
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
 */
object LLMJudgeGuardrailExample extends App {
  private val logger = LoggerFactory.getLogger(getClass)

  // An output guardrail that fails ends the run as AgentStatus.Blocked, not a Left.
  private def guarded(
    client: org.llm4s.llmconnect.LLMClient,
    id: String,
    guardrails: Seq[org.llm4s.agent.guardrails.OutputGuardrail],
    query: String
  ): org.llm4s.types.Result[AgentResult] =
    Agent
      .builder(id, client)
      .withMiddleware(GuardrailMiddleware(Seq.empty, guardrails))
      .build()
      .flatMap(_.run(query))

  logger.info("=== LLM-as-Judge Guardrail Example ===")

  val result = for {
    providerCfg     <- Llm4sConfig.defaultProvider()
    registryService <- Llm4sConfig.modelRegistryService()
    given org.llm4s.model.ModelRegistryService = registryService
    client <- LLMConnect.getClient(providerCfg)
    // === Example 1: Professional Tone Validation ===
    _ = logger.info("1. Testing Professional Tone Guardrail")
    _ = logger.info("-" * 40)

    // LLM evaluates if the response maintains a professional tone
    toneGuardrail = LLMToneGuardrail.professional(client, threshold = 0.7)

    state1 <- guarded(
      client,
      "llm-judge-state1",
      Seq(toneGuardrail),
      "Write a brief professional email response declining a meeting invitation."
    )

    _ = printResult(state1, "Professional Tone Check")

    // === Example 2: Safety Guardrail ===
    _ = logger.info("2. Testing Safety Guardrail")
    _ = logger.info("-" * 40)

    safetyGuardrail = LLMSafetyGuardrail(client, threshold = 0.8)

    state2 <- guarded(client, "llm-judge-state2", Seq(safetyGuardrail), "Explain how to make a simple paper airplane.")

    _ = printResult(state2, "Safety Check")

    // === Example 3: Quality Assessment ===
    _ = logger.info("3. Testing Response Quality Guardrail")
    _ = logger.info("-" * 40)

    originalQuery    = "What are the benefits of functional programming?"
    qualityGuardrail = LLMQualityGuardrail(client, originalQuery, threshold = 0.7)

    state3 <- guarded(client, "llm-judge-state3", Seq(qualityGuardrail), originalQuery)

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

    state4 <- guarded(
      client,
      "llm-judge-state4",
      Seq(customGuardrail),
      "Give me tips for learning a new programming language."
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

    state5 <- guarded(client, "llm-judge-state5", combinedGuardrails, "Describe the water cycle in nature.")

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

  def printResult(state: AgentResult, checkName: String): Unit =
    state.status match {
      case AgentStatus.Completed(answer) => printPassed(answer, checkName)
      case other => logger.warn("✗ {} did not pass: {}", checkName, AgentResults.describe(other))
    }

  private def printPassed(response: String, checkName: String): Unit = {
    val preview = if (response.length > 200) response.take(200) + "..." else response

    logger.info("✓ {} PASSED", checkName)
    logger.info("Response preview: {}", preview)
  }

  logger.info("=" * 50)
}
