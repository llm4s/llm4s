package org.llm4s.agent.delegation

import org.llm4s.agent.AgentStatus
import org.llm4s.agent.graph.{ Breakpoint, BreakpointPhase, InterruptId, NodeId }
import org.llm4s.agent.graph.tool.AgentToolSpec
import org.llm4s.agent.graph.toolloop.{
  ApprovalDecision,
  ApprovalRequest,
  BreakpointRequest,
  MiddlewareQuestionRequest,
  ToolQuestionRequest
}
import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.ToolCall
import org.llm4s.types.Result
import upickle.default.ReadWriter

import scala.util.Try

/**
 * One interrupt a suspended child waits on (design 4.46): the child's own interrupt id and its request, each as the
 * child's own `AgentStatus.Suspended` gives it. `Delegated` is one of the child's own delegated interrupts - a
 * grandchild's - under the child's composite id (`<child interrupt>~<grandchild interrupt>`).
 */
@Experimental
enum ChildInterrupt derives ReadWriter:

  /** The child's interrupt id. */
  def id: InterruptId

  /** A tool call of the child waiting for approval. */
  case Approval(id: InterruptId, request: ApprovalRequest)

  /** A question one of the child's tools asked. */
  case Question(id: InterruptId, request: ToolQuestionRequest)

  /** A question one of the child's middleware asked. */
  case MiddlewareQuestion(id: InterruptId, request: MiddlewareQuestionRequest)

  /** A task of the child a static breakpoint holds. */
  case Breakpoint(id: InterruptId, request: BreakpointRequest)

  /** An interrupt of one of the child's own children, under the child's composite id. */
  case Delegated(id: InterruptId, request: DelegatedRequest)

object ChildInterrupt:

  /** Every interrupt `status` waits on, in the order it lists them: approvals, questions, middleware questions, breakpoints, then delegated ones. */
  def of(status: AgentStatus.Suspended): Vector[ChildInterrupt] =
    status.approvals.map(Approval(_, _)) ++
      status.questions.map(Question(_, _)) ++
      status.middlewareQuestions.map(MiddlewareQuestion(_, _)) ++
      status.breakpoints.map(Breakpoint(_, _)) ++
      status.delegated.map(Delegated(_, _))

  private given ReadWriter[InterruptId] = Codecs.interruptId

  private given ReadWriter[BreakpointRequest] =
    upickle.default
      .readwriter[Codecs.HeldTask]
      .bimap(
        r => Codecs.HeldTask(r.node.value, r.phase, r.call),
        h => BreakpointRequest(NodeId(h.node), h.phase, h.call)
      )

/**
 * A suspended child's interrupt, as its parent's `AgentStatus.Suspended.delegated` lists it under a composite id
 * (design 4.46): the child run that waits, and its `request`, never itself `Delegated` - a grandchild's interrupt names
 * the grandchild here.
 */
@Experimental
final case class DelegatedRequest(child: ChildRef, request: ChildInterrupt) derives ReadWriter

/**
 * The question a `task` tool asks when its child suspends (design 4.46): the child run, and every interrupt it waits
 * on. Its answer is a [[DelegatedAnswers]]. Application code does not answer it directly: the parent's
 * `AgentStatus.Suspended.delegated` lists each pending child interrupt under a composite id, which the answer helpers
 * (`approve`, `reject`, `edit`, `reply`, `proceed`) and `Agent.resume` take unchanged.
 */
@Experimental
final case class DelegatedQuestion(child: ChildRef, pending: Vector[ChildInterrupt]) derives ReadWriter

object DelegatedQuestion:

  /** Whether a tool spec asks this question: the `task` tools, whose asks the parent lists as delegated. */
  private[agent] def asks(spec: AgentToolSpec[?]): Boolean =
    spec.question.exists(_.questionCodec eq summon[ReadWriter[DelegatedQuestion]])

  /**
   * The entries of the parent's `delegated` for the parent interrupt `parent` asking `question`: each child interrupt
   * under `<parent>~<child id>` - a grandchild's under `<parent>~<child>~<grandchild>`, naming the grandchild.
   */
  private[agent] def flatten(
    parent: InterruptId,
    question: DelegatedQuestion
  ): Vector[(InterruptId, DelegatedRequest)] =
    question.pending.map {
      case ChildInterrupt.Delegated(id, nested) => DelegatedAnswers.composite(parent, id) -> nested
      case leaf => DelegatedAnswers.composite(parent, leaf.id) -> DelegatedRequest(question.child, leaf)
    }

/**
 * The answers to a [[DelegatedQuestion]] (design 4.46), keyed by the child's interrupt ids - composite ones for the
 * child's own delegated interrupts - each encoded as the child expects it.
 */
@Experimental
final case class DelegatedAnswers(answers: Map[InterruptId, ujson.Value])

object DelegatedAnswers:
  given ReadWriter[DelegatedAnswers] =
    upickle.default
      .readwriter[Map[String, ujson.Value]]
      .bimap(
        _.answers.map((id, value) => id.value -> value),
        m => DelegatedAnswers(m.map((k, v) => InterruptId(k) -> v))
      )

  /** What separates a parent's interrupt id from its child's in a composite id. */
  val Separator: Char = '~'

  /** The composite id of the child interrupt `child` under the parent interrupt `parent`. */
  def composite(parent: InterruptId, child: InterruptId): InterruptId =
    InterruptId(s"${parent.value}$Separator${child.value}")

  /**
   * `answers` as one thread takes them (design 4.46): each composite answer `<p>~<rest>` grouped with the others under
   * `p` into the [[DelegatedAnswers]] `p`'s `task` call takes, keyed by `<rest>`; every other answer as it is. An
   * interrupt answered both directly and through composite ids is refused.
   */
  private[agent] def group(answers: Map[InterruptId, ujson.Value]): Result[Map[InterruptId, ujson.Value]] =
    val (nested, direct) = answers.partition((id, _) => id.value.contains(Separator))
    val grouped = nested.toVector
      .map { (id, value) =>
        val (parent, rest) = id.value.splitAt(id.value.indexOf(Separator))
        InterruptId(parent) -> (InterruptId(rest.drop(1)) -> value)
      }
      .groupMap(_._1)(_._2)
    val both = grouped.keySet.intersect(direct.keySet)
    if both.nonEmpty then
      Left(
        ValidationError(
          "answers",
          s"interrupt(s) ${both.map(_.value).toVector.sorted.mkString(", ")} answered both directly and through " +
            "their delegated ids"
        )
      )
    else
      Right(
        direct ++ grouped.map((parent, entries) => parent -> upickle.default.writeJs(DelegatedAnswers(entries.toMap)))
      )

  /**
   * The problems with `answers` (design 4.46), against `pending`, the thread's delegated interrupts: a composite id
   * that is not pending, an approval's answer that is not a decision, or a breakpoint's that is not
   * `Breakpoint.proceed`. A thread's other answers are checked as the runtime admits them.
   */
  private[agent] def problems(
    answers: Map[InterruptId, ujson.Value],
    pending: Vector[(InterruptId, DelegatedRequest)]
  ): Vector[String] =
    val byId = pending.toMap
    answers.toVector
      .filter((id, _) => id.value.contains(Separator))
      .sortBy(_._1.value)
      .flatMap { (id, answer) =>
        byId.get(id) match
          case None => Some(s"interrupt '${id.value}' is not pending")
          case Some(DelegatedRequest(_, _: ChildInterrupt.Approval))
              if Try(upickle.default.read[ApprovalDecision](answer)).isFailure =>
            Some(s"the answer to approval '${id.value}' is not an approval decision")
          case Some(DelegatedRequest(_, _: ChildInterrupt.Breakpoint)) if answer != Breakpoint.proceed =>
            Some(s"the answer to breakpoint '${id.value}' must be null (Breakpoint.proceed)")
          case _ => None
      }

/** Codecs for the ids and requests the delegated types carry. */
private object Codecs:
  val interruptId: ReadWriter[InterruptId] = upickle.default.readwriter[String].bimap(_.value, InterruptId(_))

  /** A [[BreakpointRequest]] as stored in a [[DelegatedQuestion]]. */
  final case class HeldTask(node: String, phase: BreakpointPhase, call: Option[ToolCall]) derives ReadWriter
