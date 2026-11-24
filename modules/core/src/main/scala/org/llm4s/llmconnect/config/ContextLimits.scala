package org.llm4s.llmconnect.config

import org.llm4s.annotation.Stable

/**
 * A model's token limits, as [[ContextWindowResolver.resolve]] resolves them.
 *
 * @param contextWindow     the tokens a prompt and its completion may take together.
 * @param reserveCompletion the tokens held back for the completion.
 *
 * Build one with [[ContextLimits.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers.
 */
@Stable
final case class ContextLimits private (contextWindow: Int, reserveCompletion: Int) {

  /** Sets the tokens a prompt and its completion may take together. */
  def withContextWindow(contextWindow: Int): ContextLimits = copy(contextWindow = contextWindow)

  /** Sets the tokens held back for the completion. */
  def withReserveCompletion(reserveCompletion: Int): ContextLimits = copy(reserveCompletion = reserveCompletion)
}

object ContextLimits {

  /** Creates a [[ContextLimits]]. */
  def apply(contextWindow: Int, reserveCompletion: Int): ContextLimits =
    new ContextLimits(contextWindow, reserveCompletion)
}
