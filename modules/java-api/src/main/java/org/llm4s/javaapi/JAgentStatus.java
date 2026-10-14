package org.llm4s.javaapi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * How an agent turn ended, or what it waits for: a {@link JAgentResult}'s status. {@link #kind()} says which
 * case it is - a Java enum, for a {@code switch} or a Kotlin {@code when} - and each case's data has an
 * accessor that is empty for the other cases.
 *
 * <pre>{@code
 * JAgentStatus s = result.status();
 * switch (s.kind()) {
 *     case COMPLETED          -> System.out.println(s.answer().orElseThrow());
 *     case BLOCKED            -> System.out.println(s.guardrail().orElseThrow() + ": " + s.reason().orElseThrow());
 *     case STEP_LIMIT_REACHED -> System.out.println("Hit the step limit");
 *     case SUSPENDED          -> s.pending().forEach(p -> answers.add(Answer.approve(p.id())));
 * }
 * }</pre>
 *
 * <p>A value: two are equal when every field is. {@code toString} prints the full answer or reason, as the
 * Scala {@code AgentStatus} does. Not {@code Serializable}. Built only by this package - its constructor is
 * package-private, so a caller cannot build a status the library never would, such as a {@code COMPLETED}
 * one with no answer, and fields can be added without breaking a caller (#1666).
 */
public final class JAgentStatus {

  private final AgentStatusKind kind;
  private final Optional<String> answer;
  private final Optional<String> guardrail;
  private final Optional<String> reason;
  private final List<PendingInterrupt> pending;

  JAgentStatus(
      AgentStatusKind kind,
      Optional<String> answer,
      Optional<String> guardrail,
      Optional<String> reason,
      List<PendingInterrupt> pending) {
    this.kind = kind;
    this.answer = answer;
    this.guardrail = guardrail;
    this.reason = reason;
    this.pending = pending;
  }

  /** Which case this is. */
  public AgentStatusKind kind() {
    return kind;
  }

  /** The final answer, for {@code COMPLETED}; empty otherwise. */
  public Optional<String> answer() {
    return answer;
  }

  /** The name of the first guardrail that refused the turn, for {@code BLOCKED}; empty otherwise. */
  public Optional<String> guardrail() {
    return guardrail;
  }

  /** Every refusing guardrail's error, for {@code BLOCKED}; empty otherwise. */
  public Optional<String> reason() {
    return reason;
  }

  /**
   * The approvals, then the tool questions, middleware questions and breakpoints, the turn waits for, for
   * {@code SUSPENDED} (the same list as {@code JAgent.pending}); an empty, unmodifiable list otherwise.
   */
  public List<PendingInterrupt> pending() {
    return pending;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JAgentStatus that
        && kind == that.kind
        && answer.equals(that.answer)
        && guardrail.equals(that.guardrail)
        && reason.equals(that.reason)
        && pending.equals(that.pending);
  }

  @Override
  public int hashCode() {
    return Objects.hash(kind, answer, guardrail, reason, pending);
  }

  @Override
  public String toString() {
    return switch (kind) {
      case COMPLETED -> "COMPLETED(" + answer.orElse("") + ")";
      case BLOCKED -> "BLOCKED(" + guardrail.orElse("") + ": " + reason.orElse("") + ")";
      case SUSPENDED -> "SUSPENDED(" + pending.size() + " pending)";
      default -> kind.name();
    };
  }
}
