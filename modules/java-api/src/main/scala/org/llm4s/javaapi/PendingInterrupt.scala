package org.llm4s.javaapi

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.{ BreakpointPhase => GraphPhase, InterruptId }
import org.llm4s.agent.graph.toolloop.{
  ApprovalRequest,
  BreakpointRequest,
  MiddlewareQuestionRequest,
  ToolQuestionRequest
}
import org.llm4s.llmconnect.model.ToolCall

import java.util.{ Objects, Optional }
import scala.jdk.CollectionConverters.*

/**
 * One thing a suspended agent turn waits for - an approval, a tool's or a middleware's question, or a
 * task a static breakpoint holds - as a `SUSPENDED` [[JAgentStatus.pending]] (and its shortcut
 * [[JAgent.pending]]) lists them: every field a `String` or a Java enum, JSON as text, and a field that
 * only some kinds have an `Optional`.
 *
 * {{{
 * for (PendingInterrupt p : result.status().pending()) {
 *     switch (p.kind()) {
 *         case APPROVAL            -> answers.add(Answer.approve(p.id()));
 *         case QUESTION            -> answers.add(Answer.reply(p.id(), "true"));
 *         case MIDDLEWARE_QUESTION -> answers.add(Answer.reply(p.id(), "{\"value\":\"eu\"}"));
 *         case BREAKPOINT          -> answers.add(Answer.proceed(p.id()));
 *     }
 * }
 * agent.resume(result.threadId(), answers);
 * }}}
 *
 * A value: two are equal when every field is.
 *
 * @param id the interrupt's id, to answer it with [[Answer]]
 * @param kind what it waits for
 */
final class PendingInterrupt private (
  val id: String,
  val kind: InterruptKind,
  private val call: Option[ToolCall],
  private val detail: Option[String],
  private val asker: Option[String],
  private val held: Option[(String, BreakpointPhase)]
) {

  /**
   * The name of the tool whose call waits: always for an `APPROVAL` or a `QUESTION`; for a
   * `MIDDLEWARE_QUESTION` asked about a tool call, and a `BREAKPOINT` holding a tool call before it runs.
   */
  def toolName(): Optional[String] = Optional.ofNullable(call.map(_.name).orNull)

  /**
   * That call's arguments as JSON text, when [[toolName]] is present - an object, as a model sends them,
   * though a call built with a `ujson.Str` renders as a JSON string literal.
   */
  def argumentsJson(): Optional[String] = Optional.ofNullable(call.map(_.arguments.render()).orNull)

  /** Why the call needs approval, for an `APPROVAL` that gives a reason; empty for every other kind. */
  def reason(): Optional[String] = when(InterruptKind.APPROVAL)

  /**
   * The question, as JSON text in the asker's question type, for a `QUESTION` or a `MIDDLEWARE_QUESTION`
   * that has one; empty for every other kind. [[Answer.reply]] takes the answer as JSON text of the
   * asker's answer type.
   */
  def questionJson(): Optional[String] =
    if (kind == InterruptKind.MIDDLEWARE_QUESTION) when(InterruptKind.MIDDLEWARE_QUESTION)
    else when(InterruptKind.QUESTION)

  /** The id of the middleware that asked, for a `MIDDLEWARE_QUESTION`; empty for every other kind. */
  def middleware(): Optional[String] = Optional.ofNullable(asker.orNull)

  /** The id of the node whose task a `BREAKPOINT` holds, such as `assistant/call-tool`; empty for every other kind. */
  def node(): Optional[String] = Optional.ofNullable(held.map(_._1).orNull)

  /** Whether a `BREAKPOINT` holds its task before or after it ran; empty for every other kind. */
  def phase(): Optional[BreakpointPhase] = Optional.ofNullable(held.map(_._2).orNull)

  private def when(wanted: InterruptKind): Optional[String] =
    if (kind == wanted) Optional.ofNullable(detail.orNull) else Optional.empty()

  private def fields: List[Any] = List(id, kind, call, detail, asker, held)

  override def equals(other: Any): Boolean = other match {
    case that: PendingInterrupt => fields == that.fields
    case _                      => false
  }

  override def hashCode: Int = Objects.hash(fields.map(_.asInstanceOf[AnyRef])*)

  override def toString: String = {
    val what = held.map((node, phase) => s"$phase $node").orElse(asker.map(m => s"middleware $m")).getOrElse("")
    val tool = call.fold("")(c => s"${c.name} ${c.arguments.render()}")
    s"PendingInterrupt($kind $id: ${Seq(what, tool).filter(_.nonEmpty).mkString(" ")})"
  }
}

object PendingInterrupt {

  /**
   * The interrupts `status` waits for: a `Suspended` turn's approvals, then its tool questions, its
   * middleware questions and its breakpoints, each in the order the turn lists them; none for any other
   * status. An unmodifiable `java.util.List`.
   */
  private[javaapi] def of(status: AgentStatus): java.util.List[PendingInterrupt] = status match {
    case AgentStatus.Suspended(approvals, questions, asked, breakpoints) =>
      val pending = approvals.map((id, request) => approval(id, request)) ++
        questions.map((id, request) => question(id, request)) ++
        asked.map((id, request) => middlewareQuestion(id, request)) ++
        breakpoints.map((id, request) => breakpoint(id, request))
      java.util.List.copyOf(pending.asJava)
    case _ => java.util.List.of()
  }

  private def approval(id: InterruptId, request: ApprovalRequest): PendingInterrupt =
    new PendingInterrupt(id.value, InterruptKind.APPROVAL, Some(request.call), Option(request.reason), None, None)

  private def question(id: InterruptId, request: ToolQuestionRequest): PendingInterrupt =
    new PendingInterrupt(
      id.value,
      InterruptKind.QUESTION,
      Some(request.call),
      Option(request.question).map(_.render()),
      None,
      None
    )

  private def middlewareQuestion(id: InterruptId, request: MiddlewareQuestionRequest): PendingInterrupt =
    new PendingInterrupt(
      id.value,
      InterruptKind.MIDDLEWARE_QUESTION,
      request.call.map(_.call),
      Option(request.question).map(_.render()),
      Some(request.middleware.value),
      None
    )

  private def breakpoint(id: InterruptId, request: BreakpointRequest): PendingInterrupt = {
    val phase = request.phase match {
      case GraphPhase.Before => BreakpointPhase.BEFORE
      case GraphPhase.After  => BreakpointPhase.AFTER
    }
    new PendingInterrupt(
      id.value,
      InterruptKind.BREAKPOINT,
      request.call,
      None,
      None,
      Some(request.node.value -> phase)
    )
  }
}
