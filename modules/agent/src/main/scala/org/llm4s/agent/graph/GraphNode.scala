package org.llm4s.agent.graph

import org.llm4s.error.LLMError
import org.llm4s.types.{ Result, TryOps }
import upickle.default.ReadWriter

/**
 * A handle to a node that consumes `I`, issued by a [[GraphBuilder]].
 *
 * Handles are the only way to route: a node cannot name another by string. `Goto` takes a
 * `NodeRef[Unit]` and `Send` a payload of the target's input type, so a mis-typed route does not
 * compile. A handle from another builder is rejected when the graph compiles, or when a node
 * returns it at run time.
 */
final class NodeRef[I] private[graph] (
  val id: NodeId,
  private[graph] val owner: GraphOwner,
  private[graph] val codec: ReadWriter[I],
  private[graph] val inputVersion: SchemaVersion
):
  override def toString: String = s"NodeRef(${id.value})"

/**
 * A barrier that schedules `target` once every source node has completed a task since its last
 * release. Each source counts once per activation, however many of its tasks complete.
 */
final class StaticJoin private[graph] (
  val id: JoinId,
  val sources: Set[NodeId],
  val target: NodeRef[Unit],
  private[graph] val owner: GraphOwner
):
  override def toString: String = s"StaticJoin(${id.value})"

/**
 * A barrier attached to a fan-out. A [[Route.FanOut]] opens an activation recording the task id
 * of every child it schedules; `target` is scheduled once all of them have completed. An empty
 * fan-out releases immediately.
 */
final class DynamicJoin private[graph] (
  val id: JoinId,
  val target: NodeRef[Unit],
  private[graph] val owner: GraphOwner
):
  override def toString: String = s"DynamicJoin(${id.value})"

/** Identity of the builder that issued a handle; handles compare owners by reference. */
final private[graph] class GraphOwner(val graphId: String)

/** A routing decision returned by a node; scheduled for the next superstep. */
enum Route:
  case Goto(target: NodeRef[Unit])
  case Send[I](target: NodeRef[I], payload: I) extends Route

  /** One child task per payload, in order, each counted by the join's activation. */
  case FanOut[I](join: DynamicJoin, target: NodeRef[I], payloads: Vector[I]) extends Route

/**
 * A node's state update and routes. Static edges declared on the builder are additive: they are
 * scheduled before the command's routes. A command with no routes and no outgoing edges ends
 * that branch; the run completes when no branch has work left.
 */
final case class Command(update: StateUpdate, routes: List[Route]):
  def update[A, U](key: StateKey[A, U], value: U): Command = copy(update = update.update(key, value))
  def remove(key: StateKey[?, ?]): Command                 = copy(update = update.remove(key))
  def goto(target: NodeRef[Unit]): Command                 = copy(routes = routes :+ Route.Goto(target))
  def send[I](target: NodeRef[I], payload: I): Command     = copy(routes = routes :+ Route.Send(target, payload))
  def fanOut[I](join: DynamicJoin, target: NodeRef[I], payloads: Vector[I]): Command =
    copy(routes = routes :+ Route.FanOut(join, target, payloads))

object Command:
  val empty: Command = Command(StateUpdate.empty, Nil)

/** What a node task produced. */
enum NodeResult:
  case Continue(command: Command)

  /**
   * Parks this task's work until `question` is answered. `update` commits with the superstep like
   * any other; the run then pauses at the end of the superstep - siblings finish and commit, but
   * no further superstep starts until a resume. The answer schedules `resumeAt` with
   * `Resumed(question, answer)`. That continuation stands in for this task: it fills this task's
   * join arrival, so a barrier this task belongs to stays closed until the continuation completes.
   */
  case Suspend[Q, A](update: StateUpdate, question: Q, resumeAt: ResumeRef[Q, A]) extends NodeResult
  case Fail(error: LLMError)

object NodeResult:
  def fromResult(result: Result[Command]): NodeResult = result.fold(Fail(_), Continue(_))

/** Where a task's events go; the runtime gives each task its own. */
private[graph] trait NodeEventSink:
  def custom(name: String, version: Int, payload: ujson.Value): Unit
  def progress(payload: ujson.Value): Unit

private[graph] object NodeEventSink:
  val none: NodeEventSink = new NodeEventSink:
    def custom(name: String, version: Int, payload: ujson.Value): Unit = ()
    def progress(payload: ujson.Value): Unit                           = ()

/**
 * A node's behaviour. It reads the superstep's committed snapshot - never another task's
 * uncommitted writes - and returns its effects as data.
 */
trait GraphNode[I]:
  def run(input: I, state: ThreadState, context: RunContext): NodeResult

/** A suspended task's question with the answer it was resumed with. */
final case class Resumed[Q, A](question: Q, answer: A)

/**
 * A handle to a node that continues suspended work: it consumes `Resumed[Q, A]`. Issued by
 * [[GraphBuilder.declareResume]]; a node may suspend only to a resume handle. The question and
 * answer codecs belong to the graph - a checkpoint stores the question as JSON and a resume
 * supplies the answer as JSON, and both are decoded here.
 */
final class ResumeRef[Q, A] private[graph] (
  val node: NodeRef[Resumed[Q, A]],
  private[graph] val questionCodec: ReadWriter[Q],
  private[graph] val answerCodec: ReadWriter[A],
  private[graph] val questionVersion: SchemaVersion
):
  /** Encodes an answer for [[CompiledGraph.resume]] or [[GraphRuntime.resume]]. */
  def answer(value: A): ujson.Value = upickle.default.writeJs(value)(using answerCodec)

  override def toString: String = s"ResumeRef(${node.id.value})"

  private[graph] def encodeQuestion(question: Any): VersionedJson =
    VersionedJson(questionVersion.current, upickle.default.writeJs(question.asInstanceOf[Q])(using questionCodec))

  private[graph] def decodeQuestion(json: VersionedJson): Result[Q] =
    questionVersion
      .upgrade(json.version, json.value)
      .flatMap(v => scala.util.Try(upickle.default.read[Q](v)(using questionCodec)).toResult)

  private[graph] def decodeAnswer(json: ujson.Value): Result[A] =
    scala.util.Try(upickle.default.read[A](json)(using answerCodec)).toResult

private[graph] object ResumeRef:
  def resumedCodec[Q, A](using q: ReadWriter[Q], a: ReadWriter[A]): ReadWriter[Resumed[Q, A]] =
    upickle.default
      .readwriter[ujson.Value]
      .bimap[Resumed[Q, A]](
        r =>
          ujson.Obj("question" -> upickle.default.writeJs(r.question), "answer" -> upickle.default.writeJs(r.answer)),
        json => Resumed(upickle.default.read[Q](json("question")), upickle.default.read[A](json("answer")))
      )

/** A parked continuation, as reported by a suspended run. */
final case class PendingInterrupt(id: InterruptId, resumeNode: NodeId, question: ujson.Value)
