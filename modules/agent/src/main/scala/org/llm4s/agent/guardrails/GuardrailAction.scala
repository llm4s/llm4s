package org.llm4s.agent.guardrails

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
sealed trait GuardrailAction

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

/**
 * Result of a guardrail check with action handling.
 *
 * Extends the basic Result type with information about what action
 * was taken and any warnings that were logged.
 */
sealed trait GuardrailResult[+A]

object GuardrailResult {

  /**
   * Validation passed without any issues.
   */
  final case class Passed[A](value: A) extends GuardrailResult[A]

  /**
   * Violation was automatically fixed.
   *
   * @param original The original value before fix
   * @param fixed The fixed value
   * @param violations Description of what was fixed
   */
  final case class Fixed[A](
    original: A,
    fixed: A,
    violations: Seq[String]
  ) extends GuardrailResult[A]

  /**
   * Violation was detected but processing continues (warn mode).
   *
   * @param value The original value (unchanged)
   * @param violations Description of the violations
   */
  final case class Warned[A](
    value: A,
    violations: Seq[String]
  ) extends GuardrailResult[A]

  /**
   * Validation failed and processing should stop.
   *
   * @param violations Description of the violations that caused the block
   */
  final case class Blocked(violations: Seq[String]) extends GuardrailResult[Nothing]

  /**
   * Get the value if available (Passed, Fixed, or Warned).
   */
  implicit class GuardrailResultOps[A](private val result: GuardrailResult[A]) extends AnyVal {
    def toOption: Option[A] = result match {
      case Passed(v)      => Some(v)
      case Fixed(_, v, _) => Some(v)
      case Warned(v, _)   => Some(v)
      case Blocked(_)     => None
    }

    def getOrElse[B >: A](default: => B): B = toOption.getOrElse(default)

    def isSuccess: Boolean = result match {
      case Blocked(_) => false
      case _          => true
    }

    def isBlocked: Boolean = result match {
      case Blocked(_) => true
      case _          => false
    }

    def hasWarnings: Boolean = result match {
      case Warned(_, _)    => true
      case Fixed(_, _, vs) => vs.nonEmpty
      case _               => false
    }
  }
}
