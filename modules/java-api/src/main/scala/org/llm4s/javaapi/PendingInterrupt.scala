package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.InterruptId
import org.llm4s.agent.graph.toolloop.{ ApprovalRequest, ToolQuestionRequest }

import java.util.{ Objects, Optional }
import scala.jdk.CollectionConverters.*

/**
 * One approval or question a suspended agent turn waits for, as a `SUSPENDED` [[JAgentStatus.pending]]
 * (and its shortcut [[JAgent.pending]]) lists them: every field a `String` or a Java enum, JSON as text.
 *
 * {{{
 * for (PendingInterrupt p : result.status().pending()) {
 *     switch (p.kind()) {
 *         case APPROVAL -> answers.add(Answer.approve(p.id()));
 *         case QUESTION -> answers.add(Answer.reply(p.id(), "true"));
 *     }
 * }
 * agent.resume(result.threadId(), answers);
 * }}}
 *
 * A value: two are equal when every field is.
 *
 * @param id the interrupt's id, to answer it with [[Answer]]
 * @param kind whether it waits for an approval or for the answer to a question
 * @param toolName the name of the tool whose call is waiting
 * @param argumentsJson the call's arguments, a JSON object as text
 */
final class PendingInterrupt private (
  val id: String,
  val kind: InterruptKind,
  val toolName: String,
  val argumentsJson: String,
  private val detail: String
) {

  /** Why the call needs approval, for an `APPROVAL` that gives a reason; empty for a `QUESTION`. */
  def reason(): Optional[String] = when(InterruptKind.APPROVAL)

  /**
   * The question the tool asked, as JSON text in the tool's question type, for a `QUESTION` that has
   * one; empty for an `APPROVAL`. [[Answer.reply]] takes the answer as JSON text of the tool's answer type.
   */
  def questionJson(): Optional[String] = when(InterruptKind.QUESTION)

  private def when(wanted: InterruptKind): Optional[String] =
    if (kind == wanted) Optional.ofNullable(detail) else Optional.empty()

  override def equals(other: Any): Boolean = other match {
    case that: PendingInterrupt =>
      id == that.id && kind == that.kind && toolName == that.toolName &&
      argumentsJson == that.argumentsJson && detail == that.detail
    case _ => false
  }

  override def hashCode: Int = Objects.hash(id, kind, toolName, argumentsJson, detail)

  override def toString: String = s"PendingInterrupt($kind $id: $toolName $argumentsJson)"
}

object PendingInterrupt {

  /**
   * The interrupts `status` waits for: a `Suspended` turn's approvals, then its questions, each in the
   * order the turn lists them; none for any other status. An unmodifiable `java.util.List`.
   */
  private[javaapi] def of(status: AgentStatus): java.util.List[PendingInterrupt] = status match {
    case AgentStatus.Suspended(approvals, questions) =>
      val pending = approvals.map((id, request) => approval(id, request)) ++
        questions.map((id, request) => question(id, request))
      java.util.List.copyOf(pending.asJava)
    case _ => java.util.List.of()
  }

  private def approval(id: InterruptId, request: ApprovalRequest): PendingInterrupt =
    new PendingInterrupt(
      id.value,
      InterruptKind.APPROVAL,
      request.call.name,
      request.call.arguments.render(),
      request.reason
    )

  private def question(id: InterruptId, request: ToolQuestionRequest): PendingInterrupt =
    new PendingInterrupt(
      id.value,
      InterruptKind.QUESTION,
      request.call.name,
      request.call.arguments.render(),
      Option(request.question).map(_.render()).orNull
    )
}
