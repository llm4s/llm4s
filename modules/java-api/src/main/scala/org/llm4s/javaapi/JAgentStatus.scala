package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus

import java.util.{ Objects, Optional }

/**
 * How an agent turn ended, or what it waits for: a [[JAgentResult]]'s status. [[kind]] says which
 * case it is - a Java enum, for a `switch` or a Kotlin `when` - and each case's data has an accessor
 * that is empty for the other cases.
 *
 * {{{
 * JAgentStatus s = result.status();
 * switch (s.kind()) {
 *     case COMPLETED          -> System.out.println(s.answer().orElseThrow());
 *     case BLOCKED            -> System.out.println(s.guardrail().orElseThrow() + ": " + s.reason().orElseThrow());
 *     case STEP_LIMIT_REACHED -> System.out.println("Hit the step limit");
 *     case SUSPENDED          -> s.pending().forEach(p -> answers.add(Answer.approve(p.id())));
 * }
 * }}}
 *
 * A value: two are equal when every field is. `toString` prints the full answer or reason, as the Scala
 * `AgentStatus` does. Not `Serializable`.
 *
 * @param kind which case this is
 * @param answer the final answer, for `COMPLETED`
 * @param guardrail the name of the first guardrail that refused the turn, for `BLOCKED`
 * @param reason every refusing guardrail's error, for `BLOCKED`
 * @param pending the approvals, then the tool questions, middleware questions and breakpoints, the turn
 *                waits for, for `SUSPENDED` (the same list as [[JAgent.pending]]); an empty, unmodifiable
 *                list otherwise
 */
final class JAgentStatus private (
  val kind: AgentStatusKind,
  val answer: Optional[String],
  val guardrail: Optional[String],
  val reason: Optional[String],
  val pending: java.util.List[PendingInterrupt]
) {

  private def fields: List[Any] = List(kind, answer, guardrail, reason, pending)

  override def equals(other: Any): Boolean = other match {
    case that: JAgentStatus => fields == that.fields
    case _                  => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String = kind match {
    case AgentStatusKind.COMPLETED => s"COMPLETED(${answer.orElse("")})"
    case AgentStatusKind.BLOCKED   => s"BLOCKED(${guardrail.orElse("")}: ${reason.orElse("")})"
    case AgentStatusKind.SUSPENDED => s"SUSPENDED(${pending.size} pending)"
    case other                     => other.name
  }
}

object JAgentStatus {

  /** `status` as Java and Kotlin callers read it; a `Suspended` one's interrupts via [[PendingInterrupt.of]]. */
  private[javaapi] def of(status: AgentStatus): JAgentStatus = status match {
    case AgentStatus.Completed(answer) =>
      new JAgentStatus(AgentStatusKind.COMPLETED, Optional.ofNullable(answer), Optional.empty, Optional.empty, none)
    case AgentStatus.Blocked(guardrail, reason) =>
      new JAgentStatus(
        AgentStatusKind.BLOCKED,
        Optional.empty,
        Optional.ofNullable(guardrail),
        Optional.ofNullable(reason),
        none
      )
    case AgentStatus.StepLimitReached =>
      new JAgentStatus(AgentStatusKind.STEP_LIMIT_REACHED, Optional.empty, Optional.empty, Optional.empty, none)
    case suspended: AgentStatus.Suspended =>
      new JAgentStatus(
        AgentStatusKind.SUSPENDED,
        Optional.empty,
        Optional.empty,
        Optional.empty,
        PendingInterrupt.of(suspended)
      )
  }

  private def none: java.util.List[PendingInterrupt] = java.util.List.of()
}
