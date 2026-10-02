package org.llm4s.agent.graph

import org.llm4s.error.LLMError
import upickle.default.ReadWriter

/**
 * A graph run paused at a superstep boundary: the committed state, the ready frontier and the
 * open join activations. Advance it with [[CompiledGraph.step]]; persist it with
 * [[CompiledGraph.snapshot]].
 */
final class Execution private[graph] (
  private[graph] val owner: GraphOwner,
  val superstep: Int,
  val state: ThreadState,
  private[graph] val frontier: Vector[Task],
  private[graph] val staticArrivals: Map[JoinId, Set[NodeId]],
  private[graph] val dynamicActivations: Vector[DynamicActivation]
):

  /** No task is ready: the next step completes the run or reports an unsatisfied join. */
  def isQuiescent: Boolean = frontier.isEmpty

  /** The ready tasks, in the order their updates will be applied. */
  def pendingTasks: Vector[(TaskId, NodeId)] = frontier.map(t => t.id -> t.node)

final private[graph] case class JoinSlot(join: JoinId, fanOutTask: TaskId)

final private[graph] case class Task(id: TaskId, node: NodeId, input: Any, slot: Option[JoinSlot])

final private[graph] case class DynamicActivation(
  join: JoinId,
  fanOutTask: TaskId,
  expected: Vector[TaskId],
  arrived: Set[TaskId]
):
  def isComplete: Boolean = expected.forall(arrived.contains)

/** The outcome of a run. */
enum RunResult[+O]:
  case Completed[+O](state: ThreadState, output: O, supersteps: Int) extends RunResult[O]

  /** `state` is the last committed state; the failing superstep's updates are not applied. */
  case Failed(state: ThreadState, error: LLMError) extends RunResult[Nothing]

/** The outcome of one superstep. */
enum Step[+O]:
  case Next(execution: Execution)     extends Step[Nothing]
  case Done[+O](result: RunResult[O]) extends Step[O]

/**
 * A serializable picture of an [[Execution]] - data only, no closures or codecs. Node inputs and
 * state values are encoded with the codecs of the graph that wrote them, and decoded and checked
 * against the graph that restores them.
 *
 * This is the #1267 prototype's restore format; the versioned checkpoint DTO is #1268's.
 */
final case class GraphSnapshot(
  graphId: String,
  graphVersion: String,
  fingerprint: String,
  superstep: Int,
  state: Map[String, ujson.Value],
  frontier: Vector[GraphSnapshot.PendingTask],
  staticJoins: Vector[GraphSnapshot.StaticArrivals],
  dynamicJoins: Vector[GraphSnapshot.Activation]
) derives ReadWriter

object GraphSnapshot:
  final case class PendingTask(
    taskId: String,
    nodeId: String,
    input: ujson.Value,
    joinId: Option[String],
    fanOutTask: Option[String]
  ) derives ReadWriter

  final case class StaticArrivals(joinId: String, arrived: Vector[String]) derives ReadWriter

  final case class Activation(joinId: String, fanOutTask: String, expected: Vector[String], arrived: Vector[String])
      derives ReadWriter
