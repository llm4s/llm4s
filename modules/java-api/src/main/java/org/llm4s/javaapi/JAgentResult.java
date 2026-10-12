package org.llm4s.javaapi;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The outcome of one agent turn, as {@link JAgent} and {@link AgentStream} return it: where it ran, how it
 * ended, and the thread's state at its end. Every accessor returns a {@code String}, a JDK type or another
 * type of this package, never a Scala or {@code ujson} one.
 *
 * <pre>{@code
 * JAgentResult r = agent.run("What is 2+2?").get();
 * switch (r.status().kind()) {
 *     case COMPLETED          -> System.out.println(r.answer().orElseThrow());
 *     case BLOCKED            -> System.out.println("Blocked by " + r.status().guardrail().orElseThrow());
 *     case STEP_LIMIT_REACHED -> System.out.println("Hit the step limit");
 *     case SUSPENDED          -> r.status().pending().forEach(p -> System.out.println(p.kind() + " " + p.id()));
 * }
 * }</pre>
 *
 * <p>A value: two are equal when every field is. Built only by this package - its constructor is
 * package-private, so a caller cannot make one the library never would, and fields can be added without
 * breaking a caller (#1666). A conversation continues by its {@link #threadId()}, with
 * {@code JAgent.continueConversation}, {@code resume} or {@code recover}. Neither it nor any type it reaches
 * is {@code Serializable}; {@link JMessage}, {@link JToolCall} and {@link JAgentStatus} print their full text
 * in {@code toString}, as the Scala types do, so mind what a log line includes.
 */
public final class JAgentResult {

  private final String threadId;
  private final String runId;
  private final String activeAgent;
  private final JAgentStatus status;
  private final List<JMessage> messages;
  private final JUsageSummary usage;

  JAgentResult(
      String threadId,
      String runId,
      String activeAgent,
      JAgentStatus status,
      List<JMessage> messages,
      JUsageSummary usage) {
    this.threadId = threadId;
    this.runId = runId;
    this.activeAgent = activeAgent;
    this.status = status;
    this.messages = messages;
    this.usage = usage;
  }

  /** The conversation's thread. */
  public String threadId() {
    return threadId;
  }

  /** This run. */
  public String runId() {
    return runId;
  }

  /** The id of the agent the thread is with at the run's end: the next turn starts with it. */
  public String activeAgent() {
    return activeAgent;
  }

  /** How the turn ended, or what it waits for. */
  public JAgentStatus status() {
    return status;
  }

  /** The thread's full history, without system prompts; an unmodifiable list. */
  public List<JMessage> messages() {
    return messages;
  }

  /** Token usage and cost accumulated over the thread, per model. */
  public JUsageSummary usage() {
    return usage;
  }

  /** The final answer, when the turn {@link AgentStatusKind#COMPLETED completed}; otherwise empty. */
  public Optional<String> answer() {
    return status.answer();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JAgentResult that
        && Objects.equals(threadId, that.threadId)
        && Objects.equals(runId, that.runId)
        && Objects.equals(activeAgent, that.activeAgent)
        && status.equals(that.status)
        && messages.equals(that.messages)
        && usage.equals(that.usage);
  }

  @Override
  public int hashCode() {
    return Objects.hash(threadId, runId, activeAgent, status, messages, usage);
  }

  @Override
  public String toString() {
    return "JAgentResult(" + threadId + " " + runId + ": " + status + ", " + messages.size() + " messages)";
  }
}
