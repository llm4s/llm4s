package org.llm4s.javaapi;

/**
 * How much reasoning to ask a model for, set with {@link JCompletionOptions.Builder#reasoning}.
 *
 * <p>Each constant is one of core's {@code ReasoningEffort} levels, from least to most. A model that does not reason
 * ignores it. OpenAI's reasoning models receive it as {@code reasoning_effort}; Anthropic's turn it into a thinking
 * budget, or into {@code output_config.effort} where the model takes one. A level a model does not accept is sent as
 * the nearest one it does, and reported on the completion. Whether the model thinks at all is {@link JThinking}:
 * {@code JThinking.off()} replaces the old {@code NONE}.
 *
 * <p>A Java enum, not a Scala 3 one: Java reads a Scala 3 enum's cases through its companion object,
 * and a {@code switch} that runs before that object is initialised finds them {@code null}.
 */
public enum JReasoningEffort {
  /** The least thinking a model that always thinks will do. */
  MINIMAL,

  /** A little deliberation. */
  LOW,

  /** A balance of quality and latency. */
  MEDIUM,

  /** Deep thinking, for hard tasks. */
  HIGH,

  /** More than {@code HIGH}, short of {@code MAX}. */
  XHIGH,

  /** The most reasoning a model offers. */
  MAX
}
