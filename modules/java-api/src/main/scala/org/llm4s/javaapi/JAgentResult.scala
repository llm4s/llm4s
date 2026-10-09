package org.llm4s.javaapi

import org.llm4s.agent.AgentResult

import java.util.{ Objects, Optional }
import scala.jdk.CollectionConverters.*

/**
 * The outcome of one agent turn, as [[JAgent]] and [[AgentStream]] return it: where it ran, how it
 * ended, and the thread's state at its end. Every accessor returns a `String`, a JDK type or another
 * type of this package, never a Scala or `ujson` one.
 *
 * {{{
 * JAgentResult r = agent.run("What is 2+2?").get();
 * switch (r.status().kind()) {
 *     case COMPLETED          -> System.out.println(r.answer().orElseThrow());
 *     case BLOCKED            -> System.out.println("Blocked by " + r.status().guardrail().orElseThrow());
 *     case STEP_LIMIT_REACHED -> System.out.println("Hit the step limit");
 *     case SUSPENDED          -> r.status().pending().forEach(p -> System.out.println(p.toolName()));
 * }
 * }}}
 *
 * A value: two are equal when every field is. Built only by the facade; a conversation continues by
 * its [[threadId]], with `JAgent.continueConversation`, `resume` or `recover`. Neither it nor any type it
 * reaches is `Serializable`; [[JMessage]], [[JToolCall]] and [[JAgentStatus]] print their full text in
 * `toString`, as the Scala types do, so mind what a log line includes.
 *
 * @param threadId the conversation's thread
 * @param runId this run
 * @param activeAgent the id of the agent the thread is with at the run's end: the next turn starts with it
 * @param status how the turn ended, or what it waits for
 * @param messages the thread's full history, without system prompts; an unmodifiable list
 * @param usage token usage and cost accumulated over the thread, per model
 */
final class JAgentResult private (
  val threadId: String,
  val runId: String,
  val activeAgent: String,
  val status: JAgentStatus,
  val messages: java.util.List[JMessage],
  val usage: JUsageSummary
) {

  /** The final answer, when the turn [[AgentStatusKind.COMPLETED completed]]; otherwise empty. */
  def answer(): Optional[String] = status.answer

  private def fields: List[Any] = List(threadId, runId, activeAgent, status, messages, usage)

  override def equals(other: Any): Boolean = other match {
    case that: JAgentResult => fields == that.fields
    case _                  => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String = s"JAgentResult($threadId $runId: $status, ${messages.size} messages)"
}

object JAgentResult {

  /** `result` as Java and Kotlin callers read it. */
  private[javaapi] def of(result: AgentResult): JAgentResult =
    new JAgentResult(
      result.threadId.value,
      result.runId.value,
      result.activeAgent.value,
      JAgentStatus.of(result.status),
      java.util.List.copyOf(result.messages.map(JMessage.of).asJava),
      JUsageSummary.of(result.usage)
    )
}
