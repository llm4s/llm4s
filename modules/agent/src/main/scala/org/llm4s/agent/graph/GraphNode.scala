package org.llm4s.agent.graph

import org.llm4s.error.LLMError
import org.llm4s.types.Result
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
  private[graph] val codec: ReadWriter[I]
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
  case Fail(error: LLMError)

object NodeResult:
  def fromResult(result: Result[Command]): NodeResult = result.fold(Fail(_), Continue(_))

/** Identity of the running task, for attribution and idempotency keys. */
final case class NodeContext(taskId: TaskId, nodeId: NodeId, superstep: Int)

/**
 * A node's behaviour. It reads the superstep's committed snapshot - never another task's
 * uncommitted writes - and returns its effects as data.
 */
trait GraphNode[I]:
  def run(input: I, state: ThreadState, context: NodeContext): NodeResult
