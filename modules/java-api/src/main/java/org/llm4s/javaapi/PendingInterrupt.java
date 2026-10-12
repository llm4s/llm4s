package org.llm4s.javaapi;

import java.util.Objects;
import java.util.Optional;

/**
 * One thing a suspended agent turn waits for - an approval, a tool's or a middleware's question, or a task a
 * static breakpoint holds - as a {@code SUSPENDED} {@link JAgentStatus#pending()} (and its shortcut
 * {@code JAgent.pending}) lists them: every field a {@code String} or a Java enum, JSON as text, and a field
 * that only some kinds have an {@code Optional}.
 *
 * <pre>{@code
 * for (PendingInterrupt p : result.status().pending()) {
 *     switch (p.kind()) {
 *         case APPROVAL            -> answers.add(Answer.approve(p.id()));
 *         case QUESTION            -> answers.add(Answer.reply(p.id(), "true"));
 *         case MIDDLEWARE_QUESTION -> answers.add(Answer.reply(p.id(), "{\"value\":\"eu\"}"));
 *         case BREAKPOINT          -> answers.add(Answer.proceed(p.id()));
 *     }
 * }
 * agent.resume(result.threadId(), answers);
 * }</pre>
 *
 * <p>A value: two are equal when every field is. Built only by this package - its constructor is
 * package-private, so fields can be added without breaking a caller (#1666).
 */
public final class PendingInterrupt {

  private final String id;
  private final InterruptKind kind;
  // each null when this kind has none
  private final String toolName;
  private final String argumentsJson;
  private final String detail;
  private final String middleware;
  private final String node;
  private final BreakpointPhase phase;

  /**
   * {@code detail} is an approval's reason or a question's JSON; {@code node} and {@code phase} are both set,
   * for a breakpoint, or both {@code null}.
   */
  PendingInterrupt(
      String id,
      InterruptKind kind,
      String toolName,
      String argumentsJson,
      String detail,
      String middleware,
      String node,
      BreakpointPhase phase) {
    this.id = id;
    this.kind = kind;
    this.toolName = toolName;
    this.argumentsJson = argumentsJson;
    this.detail = detail;
    this.middleware = middleware;
    this.node = node;
    this.phase = phase;
  }

  /** The interrupt's id, to answer it with {@link Answer}. */
  public String id() {
    return id;
  }

  /** What it waits for. */
  public InterruptKind kind() {
    return kind;
  }

  /**
   * The name of the tool whose call waits: always for an {@code APPROVAL} or a {@code QUESTION}; for a
   * {@code MIDDLEWARE_QUESTION} asked about a tool call, and a {@code BREAKPOINT} holding a tool call before
   * it runs.
   */
  public Optional<String> toolName() {
    return Optional.ofNullable(toolName);
  }

  /**
   * That call's arguments as JSON text, when {@link #toolName()} is present - an object, as a model sends
   * them, though a call built with a {@code ujson.Str} renders as a JSON string literal.
   */
  public Optional<String> argumentsJson() {
    return Optional.ofNullable(argumentsJson);
  }

  /** Why the call needs approval, for an {@code APPROVAL} that gives a reason; empty for every other kind. */
  public Optional<String> reason() {
    return when(InterruptKind.APPROVAL);
  }

  /**
   * The question, as JSON text in the asker's question type, for a {@code QUESTION} or a
   * {@code MIDDLEWARE_QUESTION} that has one; empty for every other kind. {@link Answer#reply} takes the
   * answer as JSON text of the asker's answer type.
   */
  public Optional<String> questionJson() {
    return kind == InterruptKind.MIDDLEWARE_QUESTION
        ? when(InterruptKind.MIDDLEWARE_QUESTION)
        : when(InterruptKind.QUESTION);
  }

  /** The id of the middleware that asked, for a {@code MIDDLEWARE_QUESTION}; empty for every other kind. */
  public Optional<String> middleware() {
    return Optional.ofNullable(middleware);
  }

  /**
   * The id of the node whose task a {@code BREAKPOINT} holds, such as {@code assistant/call-tool}; empty for
   * every other kind.
   */
  public Optional<String> node() {
    return Optional.ofNullable(node);
  }

  /** Whether a {@code BREAKPOINT} holds its task before or after it ran; empty for every other kind. */
  public Optional<BreakpointPhase> phase() {
    return Optional.ofNullable(phase);
  }

  private Optional<String> when(InterruptKind wanted) {
    return kind == wanted ? Optional.ofNullable(detail) : Optional.empty();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof PendingInterrupt that
        && Objects.equals(id, that.id)
        && kind == that.kind
        && Objects.equals(toolName, that.toolName)
        && Objects.equals(argumentsJson, that.argumentsJson)
        && Objects.equals(detail, that.detail)
        && Objects.equals(middleware, that.middleware)
        && Objects.equals(node, that.node)
        && phase == that.phase;
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, kind, toolName, argumentsJson, detail, middleware, node, phase);
  }

  @Override
  public String toString() {
    String what = node != null ? phase + " " + node : middleware != null ? "middleware " + middleware : "";
    String tool = toolName != null ? toolName + " " + argumentsJson : "";
    String both = what.isEmpty() ? tool : tool.isEmpty() ? what : what + " " + tool;
    return "PendingInterrupt(" + kind + " " + id + ": " + both + ")";
  }
}
