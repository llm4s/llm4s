package org.llm4s.javaapi;

/** Who wrote a {@link JMessage}, so a Java {@code switch} or a Kotlin {@code when} covers every role. */
public enum JMessageRole {
  /** A system prompt. */
  SYSTEM,

  /** The user's query. */
  USER,

  /** The model: its text, and the tool calls it asked for ({@link JMessage#toolCalls()}). */
  ASSISTANT,

  /** A tool's result, for the call {@link JMessage#toolCallId()} names. */
  TOOL
}
