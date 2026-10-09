package org.llm4s.javaapi;

/**
 * How an agent turn ended, or why it waits: the kind of a {@link JAgentStatus}, so a Java
 * {@code switch} or a Kotlin {@code when} covers every case.
 *
 * <p>A Java enum, not a Scala 3 one, for the reason {@link InterruptKind} gives: a {@code switch}
 * that runs before a Scala 3 enum's companion object is initialised finds its cases {@code null}.
 */
public enum AgentStatusKind {
  /** The agent gave a final answer: {@link JAgentStatus#answer()}. */
  COMPLETED,

  /**
   * A guardrail refused the turn: {@link JAgentStatus#guardrail()} names it and
   * {@link JAgentStatus#reason()} says why. The thread stays usable.
   */
  BLOCKED,

  /** The turn used the agent's step limit of model calls without a final answer. */
  STEP_LIMIT_REACHED,

  /**
   * The turn waits for tool approvals or answers to tool questions: {@link JAgentStatus#pending()}
   * lists them. Answer them with {@link Answer} and continue with {@code JAgent.resume}.
   */
  SUSPENDED
}
