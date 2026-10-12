package org.llm4s.javaapi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One message of an agent thread's history, as {@link JAgentResult#messages()} lists them: its
 * {@link #role()} - a Java enum - and its text, with the tool calls an assistant message asked for and the
 * call a tool message answers.
 *
 * <pre>{@code
 * for (JMessage m : result.messages()) {
 *     switch (m.role()) {
 *         case USER      -> System.out.println("> " + m.content());
 *         case ASSISTANT -> m.toolCalls().forEach(c -> System.out.println(c.name() + c.argumentsJson()));
 *         case TOOL      -> System.out.println(m.toolCallId().orElseThrow() + ": " + m.content());
 *         case SYSTEM    -> { }
 *     }
 * }
 * }</pre>
 *
 * <p>A value: two are equal when every field is. {@code toString} prints the full text, as the Scala
 * {@code Message} does. Not {@code Serializable}. Built only by this package - its constructor is
 * package-private, so fields can be added without breaking a caller (#1666).
 */
public final class JMessage {

  private final JMessageRole role;
  private final String content;
  private final List<JToolCall> toolCalls;
  private final Optional<String> toolCallId;
  private final Optional<String> thinking;

  JMessage(
      JMessageRole role,
      String content,
      List<JToolCall> toolCalls,
      Optional<String> toolCallId,
      Optional<String> thinking) {
    this.role = role;
    this.content = content;
    this.toolCalls = toolCalls;
    this.toolCallId = toolCallId;
    this.thinking = thinking;
  }

  /** Who wrote it. */
  public JMessageRole role() {
    return role;
  }

  /**
   * Its text; empty, never {@code null}, for an assistant message with only tool calls (and for a message
   * whose Scala text is {@code null}).
   */
  public String content() {
    return content;
  }

  /**
   * The tool calls an {@code ASSISTANT} message asked for, in order; an empty list for the other roles.
   * Unmodifiable.
   */
  public List<JToolCall> toolCalls() {
    return toolCalls;
  }

  /**
   * The id of the tool call a {@code TOOL} message answers; empty for the other roles (and for a {@code TOOL}
   * message whose id is {@code null}).
   */
  public Optional<String> toolCallId() {
    return toolCallId;
  }

  /**
   * The reasoning text an {@code ASSISTANT} message's model reported; empty when there was none, and for the
   * other roles.
   */
  public Optional<String> thinking() {
    return thinking;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JMessage that
        && role == that.role
        && content.equals(that.content)
        && toolCalls.equals(that.toolCalls)
        && toolCallId.equals(that.toolCallId)
        && thinking.equals(that.thinking);
  }

  @Override
  public int hashCode() {
    return Objects.hash(role, content, toolCalls, toolCallId, thinking);
  }

  @Override
  public String toString() {
    return role + ": " + content;
  }
}
