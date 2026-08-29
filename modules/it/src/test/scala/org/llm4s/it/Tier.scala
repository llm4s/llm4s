package org.llm4s.it

import org.scalatest.Assertions

/**
 * Availability gate for tiered integration suites.
 *
 * Every suite here needs something the machine may not have - a database, a container, a live
 * API key - and so has to decide what to do when it is missing. Skipping is right on a laptop
 * and wrong in the CI job whose entire purpose is to provide that dependency: there a silent
 * skip is indistinguishable from a pass, which is how these suites came to be trusted while
 * running nothing (see issue #1143).
 *
 * `require` resolves that by making the decision depend on who is asking. With
 * `LLM4S_IT_STRICT=true` - set by the tier's CI job - an unreachable dependency fails the
 * build. Everywhere else it cancels the test, which ScalaTest reports as skipped.
 */
object Tier {

  private val StrictEnvVar = "LLM4S_IT_STRICT"

  /** True when the caller has promised the tier's dependencies are present. */
  def strict: Boolean = sys.env.get(StrictEnvVar).exists(_.equalsIgnoreCase("true"))

  /**
   * Cancels the test when `available` is false - or fails it, if `LLM4S_IT_STRICT=true`.
   *
   * @param available whether the dependency this suite needs was reachable
   * @param what      what is missing, phrased so the message reads as a cause
   */
  def require(available: Boolean, what: String): Unit =
    if (!available) {
      if (strict)
        Assertions.fail(
          s"$what. $StrictEnvVar=true, so this tier is required to run rather than skip: " +
            "either the service failed to start or its connection settings are wrong."
        )
      else Assertions.cancel(what)
    }
}
