package org.llm4s.agent.guardrails

import org.llm4s.annotation.Experimental

/**
 * Mode for combining multiple guardrails.
 *
 * Determines how validation results are combined when multiple
 * guardrails are applied to the same value.
 */
@Experimental
sealed trait ValidationMode

@Experimental
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
   * Runs only the first guardrail and returns its result, success or failure; the others never run (#3278). An
   * empty composite passes the value unchanged. For "cheap check first, then the expensive one only if it passed",
   * use `CompositeGuardrail.sequential` (or `andThen`).
   */
  case object First extends ValidationMode
}
