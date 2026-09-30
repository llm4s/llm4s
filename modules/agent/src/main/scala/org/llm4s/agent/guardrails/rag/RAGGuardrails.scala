package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.{ Guardrail, InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.{ PIIDetector, PIIMasker, PromptInjectionDetector }
import org.llm4s.llmconnect.LLMClient

/**
 * Preset configurations for RAG guardrails.
 *
 * RAGGuardrails provides convenient preset combinations of guardrails for common
 * RAG (Retrieval-Augmented Generation) use cases. Each preset balances security,
 * quality, and latency differently.
 *
 * **Preset Levels:**
 * - `minimal`: Basic safety only (PII, input length)
 * - `standard`: Balanced protection for production use
 * - `strict`: Maximum safety with comprehensive validation
 * - `monitoring`: Full validation in warn mode (no blocking)
 *
 * Example usage:
 * {{{
 * // Get standard guardrails for production
 * val (inputGuardrails, outputGuardrails, ragGuardrails) =
 *   RAGGuardrails.standard(llmClient)
 *
 * // Use in agent
 * agent.run(
 *   query = userQuery,
 *   tools = tools,
 *   inputGuardrails = inputGuardrails,
 *   outputGuardrails = outputGuardrails
 * )
 *
 * // Use RAG guardrails separately
 * ragGuardrails.foreach { guardrail =>
 *   guardrail.validateWithContext(response, ragContext)
 * }
 * }}}
 */
object RAGGuardrails {

  /**
   * Result type for guardrail configurations.
   *
   * @param inputGuardrails Guardrails applied to user input
   * @param outputGuardrails Guardrails applied to LLM output
   * @param ragGuardrails RAG-specific guardrails with context awareness
   */
  final case class GuardrailConfig(
    inputGuardrails: Seq[InputGuardrail],
    outputGuardrails: Seq[OutputGuardrail],
    ragGuardrails: Seq[RAGGuardrail]
  )

  // ==========================================================================
  // Preset Configurations
  // ==========================================================================

  /**
   * Minimal safety configuration.
   *
   * Includes only essential protections with low latency impact:
   * - PII detection (no LLM calls)
   * - Prompt injection detection (no LLM calls)
   *
   * Best for: Low-latency applications, internal tools, testing
   */
  def minimal: GuardrailConfig = GuardrailConfig(
    inputGuardrails = Seq(
      PIIDetector(),
      PromptInjectionDetector()
    ),
    outputGuardrails = Seq(
      PIIMasker()
    ),
    ragGuardrails = Seq.empty
  )

  /**
   * Standard protection configuration.
   *
   * Balanced set of guardrails for production use:
   * - Input: PII detection, prompt injection, topic boundary
   * - Output: PII masking, grounding validation
   * - RAG: Context relevance, source attribution
   *
   * Note: LLM-based guardrails require the llmClient parameter.
   *
   * Best for: Production RAG applications
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   */
  def standard(llmClient: LLMClient): GuardrailConfig = GuardrailConfig(
    inputGuardrails = Seq(
      PIIDetector(),
      PromptInjectionDetector.balanced
    ),
    outputGuardrails = Seq(
      PIIMasker()
    ),
    ragGuardrails = Seq(
      GroundingGuardrail.balanced(llmClient),
      ContextRelevanceGuardrail.balanced(llmClient)
    )
  )

  /**
   * Standard protection with topic restrictions.
   *
   * Same as standard but adds topic boundary validation.
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   * @param allowedTopics Topics that queries should relate to
   */
  def standardWithTopics(llmClient: LLMClient, allowedTopics: Seq[String]): GuardrailConfig =
    GuardrailConfig(
      inputGuardrails = Seq(
        PIIDetector(),
        PromptInjectionDetector.balanced,
        TopicBoundaryGuardrail.balanced(llmClient, allowedTopics)
      ),
      outputGuardrails = Seq(
        PIIMasker()
      ),
      ragGuardrails = Seq(
        GroundingGuardrail.balanced(llmClient),
        ContextRelevanceGuardrail.balanced(llmClient)
      )
    )

  /**
   * Strict protection configuration.
   *
   * Maximum safety with comprehensive validation:
   * - Input: Strict PII detection, high-sensitivity injection detection, topic boundary
   * - Output: PII masking, strict grounding
   * - RAG: Strict context relevance, source attribution required
   *
   * Note: Higher latency due to multiple LLM calls.
   *
   * Best for: High-stakes applications, regulated industries
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   * @param allowedTopics Topics that queries should relate to
   */
  def strict(llmClient: LLMClient, allowedTopics: Seq[String]): GuardrailConfig = GuardrailConfig(
    inputGuardrails = Seq(
      PIIDetector.strict,
      PromptInjectionDetector.strict,
      TopicBoundaryGuardrail.strict(llmClient, allowedTopics)
    ),
    outputGuardrails = Seq(
      PIIMasker()
    ),
    ragGuardrails = Seq(
      GroundingGuardrail.strict(llmClient),
      ContextRelevanceGuardrail.strict(llmClient),
      SourceAttributionGuardrail.strict(llmClient)
    )
  )

  /**
   * Monitoring configuration.
   *
   * Full validation in warn mode (never blocks):
   * - All checks enabled
   * - Warnings logged but processing continues
   * - Useful for measuring quality without impacting users
   *
   * Best for: Development, quality measurement, gradual rollout
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   */
  def monitoring(llmClient: LLMClient): GuardrailConfig = GuardrailConfig(
    inputGuardrails = Seq(
      PIIDetector(onFail = org.llm4s.agent.guardrails.GuardrailAction.Warn),
      PromptInjectionDetector.monitoring
    ),
    outputGuardrails = Seq(
      PIIMasker()
    ),
    ragGuardrails = Seq(
      GroundingGuardrail.monitoring(llmClient),
      ContextRelevanceGuardrail.monitoring(llmClient),
      SourceAttributionGuardrail.monitoring(llmClient)
    )
  )

  // ==========================================================================
  // Domain-Specific Presets
  // ==========================================================================

  /**
   * Configuration for customer support applications.
   *
   * Optimized for:
   * - Keeping conversations on-topic
   * - Ensuring accurate responses
   * - Protecting customer PII
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   * @param productTopics Topics related to your product/service
   */
  def customerSupport(llmClient: LLMClient, productTopics: Seq[String]): GuardrailConfig =
    GuardrailConfig(
      inputGuardrails = Seq(
        PIIDetector(),
        PromptInjectionDetector.balanced,
        TopicBoundaryGuardrail.customerSupport(llmClient, productTopics)
      ),
      outputGuardrails = Seq(
        PIIMasker()
      ),
      ragGuardrails = Seq(
        GroundingGuardrail.balanced(llmClient),
        ContextRelevanceGuardrail.balanced(llmClient)
      )
    )

  /**
   * Configuration for software documentation assistants.
   *
   * Optimized for:
   * - Technical accuracy
   * - Source attribution for code examples
   * - Staying within programming topics
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   */
  def softwareDocumentation(llmClient: LLMClient): GuardrailConfig = GuardrailConfig(
    inputGuardrails = Seq(
      PromptInjectionDetector.balanced,
      TopicBoundaryGuardrail.softwareDevelopment(llmClient)
    ),
    outputGuardrails = Seq.empty,
    ragGuardrails = Seq(
      GroundingGuardrail.balanced(llmClient),
      SourceAttributionGuardrail.balanced(llmClient)
    )
  )

  /**
   * Configuration for research assistants.
   *
   * Optimized for:
   * - High accuracy and grounding
   * - Proper source attribution
   * - Lenient topic boundaries (research is broad)
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   */
  def research(llmClient: LLMClient): GuardrailConfig = GuardrailConfig(
    inputGuardrails = Seq(
      PromptInjectionDetector.balanced
    ),
    outputGuardrails = Seq(
      PIIMasker()
    ),
    ragGuardrails = Seq(
      GroundingGuardrail.strict(llmClient),
      ContextRelevanceGuardrail.balanced(llmClient),
      SourceAttributionGuardrail.strict(llmClient)
    )
  )

  /**
   * Configuration for financial applications.
   *
   * Optimized for:
   * - Strict PII/financial data protection
   * - High accuracy requirements
   * - Regulatory compliance
   *
   * @param llmClient LLM client for LLM-as-judge guardrails
   * @param allowedTopics Financial topics allowed
   */
  def financial(llmClient: LLMClient, allowedTopics: Seq[String]): GuardrailConfig =
    GuardrailConfig(
      inputGuardrails = Seq(
        PIIDetector.financial,
        PromptInjectionDetector.strict,
        TopicBoundaryGuardrail.strict(llmClient, allowedTopics)
      ),
      outputGuardrails = Seq(
        PIIMasker.financial
      ),
      ragGuardrails = Seq(
        GroundingGuardrail.strict(llmClient),
        SourceAttributionGuardrail.strict(llmClient)
      )
    )

  // ==========================================================================
  // Helper Methods
  // ==========================================================================

  /**
   * Create a custom configuration by combining existing presets.
   *
   * @param inputGuardrails Input guardrails to use
   * @param outputGuardrails Output guardrails to use
   * @param ragGuardrails RAG guardrails to use
   */
  def custom(
    inputGuardrails: Seq[InputGuardrail] = Seq.empty,
    outputGuardrails: Seq[OutputGuardrail] = Seq.empty,
    ragGuardrails: Seq[RAGGuardrail] = Seq.empty
  ): GuardrailConfig = GuardrailConfig(inputGuardrails, outputGuardrails, ragGuardrails)

  /**
   * Get all guardrails as a flat sequence (for compatibility).
   *
   * @param config The guardrail configuration
   * @return All guardrails as a sequence
   */
  def allGuardrails(config: GuardrailConfig): Seq[Guardrail[String]] =
    config.inputGuardrails ++ config.outputGuardrails ++ config.ragGuardrails
}
