package org.llm4s.javaapi;

import java.util.Objects;

/**
 * A tool call a model asked for, as {@link JMessage#toolCalls()} and {@link JCompletion#toolCalls()} list
 * them: JSON as text.
 *
 * <p>A value: two are equal when every field is. {@code toString} prints the full arguments, as the Scala
 * {@code ToolCall} does. Not {@code Serializable}. Built only by this package - its constructor is
 * package-private, so fields can be added without breaking a caller (#1666).
 */
public final class JToolCall {

  private final String id;
  private final String name;
  private final String argumentsJson;

  JToolCall(String id, String name, String argumentsJson) {
    this.id = id;
    this.name = name;
    this.argumentsJson = argumentsJson;
  }

  /** The call's id, which the {@code TOOL} message answering it carries as {@code toolCallId()}. */
  public String id() {
    return id;
  }

  /** The tool's name. */
  public String name() {
    return name;
  }

  /**
   * The call's arguments as JSON text - an object, as a model sends them, though a call built with a
   * {@code ujson.Str} renders as a JSON string literal.
   */
  public String argumentsJson() {
    return argumentsJson;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JToolCall that
        && Objects.equals(id, that.id)
        && Objects.equals(name, that.name)
        && Objects.equals(argumentsJson, that.argumentsJson);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, name, argumentsJson);
  }

  @Override
  public String toString() {
    return "JToolCall(" + id + ", " + name + ", " + argumentsJson + ")";
  }
}
