package org.llm4s.agent.guardrails

/**
 * Mode for combining multiple guardrails.
 *
 * Determines how validation results are combined when multiple
 * guardrails are applied to the same value.
 */
sealed trait ValidationMode

object ValidationMode {

  /**
   * All guardrails must pass (default).
   *
   * Runs all guardrails even if some fail, aggregating all errors.
   * This is the strictest mode - useful for safety-critical validation
   * where all checks must succeed.
   *
   * Example: Input must pass profanity filter AND length check AND custom validator
   */
  case object All extends ValidationMode

  /**
   * At least one guardrail must pass.
   *
   * Returns success on first passing guardrail.
   * Useful for OR-style validation where multiple alternatives are acceptable.
   *
   * Example: Content must be in English OR Spanish OR French
   */
  case object Any extends ValidationMode

  /**
   * Returns on first result (success or failure).
   *
   * Useful for expensive guardrails where order matters and you want
   * to avoid running all checks.
   *
   * Example: Check cheap validation first, then expensive API call only if needed
   */
  case object First extends ValidationMode
}
