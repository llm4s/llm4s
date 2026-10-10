package org.llm4s.javaapi;

/**
 * How much reasoning to ask a model for, set with {@link JCompletionOptions.Builder#reasoning}.
 *
 * <p>Each constant is one of core's {@code ReasoningEffort} levels. A model that does not reason ignores it.
 * OpenAI's reasoning models receive it as {@code reasoning_effort}; Anthropic's turn it into a thinking budget
 * unless {@link JCompletionOptions.Builder#budgetTokens} sets one.
 *
 * <p>A Java enum, not a Scala 3 one: Java reads a Scala 3 enum's cases through its companion object,
 * and a {@code switch} that runs before that object is initialised finds them {@code null}.
 */
public enum JReasoningEffort {
  /** No extra reasoning: a standard completion. */
  NONE,

  /** A little deliberation. */
  LOW,

  /** A balance of quality and latency. */
  MEDIUM,

  /** The most reasoning, for hard tasks. */
  HIGH
}
