package org.llm4s.javaapi

import org.llm4s.llmconnect.model.Thinking

import java.util.OptionalInt

/**
 * Whether a model thinks before it answers, and how its thinking is bounded, set with
 * `JCompletionOptions.Builder.thinking`: separate from [[JReasoningEffort]], which says how hard to think.
 *
 * {{{
 * JCompletionOptions.builder().thinking(JThinking.adaptive()).reasoning(JReasoningEffort.LOW).build();
 * JCompletionOptions.builder().thinking(JThinking.budget(8000)).build();
 * }}}
 *
 * One of core's `Thinking` modes: [[JThinking.off]] (do not think), [[JThinking.adaptive]] (the model decides) or
 * [[JThinking.budget]] (think within a token budget, which a provider fits to its own range). What a provider could
 * not send as asked is reported on the completion. A value: two are equal when they are the same mode and budget.
 */
final class JThinking private (token: FacadeToken, private[javaapi] val underlying: Thinking) {

  FacadeToken.check(token, "JThinking")

  /** Whether this is [[JThinking.off]]. */
  def isOff: Boolean = underlying == Thinking.Off

  /** Whether this is [[JThinking.adaptive]]. */
  def isAdaptive: Boolean = underlying == Thinking.Adaptive

  /** The budget of a [[JThinking.budget]], or empty for the other modes. */
  def budgetTokens: OptionalInt = underlying match {
    case Thinking.Budget(tokens) => OptionalInt.of(tokens)
    case _                       => OptionalInt.empty()
  }

  override def equals(other: Any): Boolean = other match {
    case that: JThinking => underlying == that.underlying
    case _               => false
  }

  override def hashCode: Int = underlying.hashCode

  override def toString: String = underlying match {
    case Thinking.Off            => "JThinking.off()"
    case Thinking.Adaptive       => "JThinking.adaptive()"
    case Thinking.Budget(tokens) => s"JThinking.budget($tokens)"
  }
}

object JThinking {

  /** Do not think. */
  def off(): JThinking = new JThinking(FacadeToken.INSTANCE, Thinking.Off)

  /** Let the model decide whether and how much to think. */
  def adaptive(): JThinking = new JThinking(FacadeToken.INSTANCE, Thinking.Adaptive)

  /**
   * Think within a budget of `tokens`. Throws `IllegalArgumentException` for `tokens <= 0`, which mean different
   * things to different providers; use [[off]] or [[adaptive]] for those.
   */
  def budget(tokens: Int): JThinking =
    Thinking
      .budget(tokens)
      .fold(e => throw new IllegalArgumentException(e.message), new JThinking(FacadeToken.INSTANCE, _))
}
