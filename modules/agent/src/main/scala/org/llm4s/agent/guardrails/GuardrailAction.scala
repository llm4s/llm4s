package org.llm4s.agent.guardrails

import org.llm4s.annotation.Experimental

/**
 * Actions to take when a guardrail detects a violation.
 *
 * Guardrails can be configured to respond to violations in different ways
 * depending on the use case and severity of the detected issue.
 *
 * Example usage:
 * {{{
 * // Block on prompt injection (security critical)
 * val injectionGuard = PromptInjectionDetector(onFail = GuardrailAction.Block)
 *
 * // Fix PII by masking (privacy preserving)
 * val piiGuard = PIIDetector(onFail = GuardrailAction.Fix)
 *
 * // Warn on low-severity issues (monitoring)
 * val lengthGuard = LengthCheck(1, 10000, onFail = GuardrailAction.Warn)
 * }}}
 */
@Experimental
sealed trait GuardrailAction

@Experimental
object GuardrailAction {

  /**
   * Block processing and return an error.
   *
   * Use for security-critical violations where continuing
   * would be dangerous (e.g., prompt injection, malicious content).
   */
  case object Block extends GuardrailAction

  /**
   * Attempt to fix the violation and continue.
   *
   * Use when automatic remediation is possible and safe
   * (e.g., masking PII, truncating long text).
   *
   * If the fix fails, falls back to Block behavior.
   */
  case object Fix extends GuardrailAction

  /**
   * Log a warning and allow processing to continue.
   *
   * Use for monitoring or low-severity issues where blocking
   * would be too disruptive (e.g., soft length limits, tone warnings).
   *
   * The violation is recorded but does not stop execution.
   */
  case object Warn extends GuardrailAction

  /**
   * Default action for security-sensitive guardrails.
   */
  val default: GuardrailAction = Block
}
