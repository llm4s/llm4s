package org.llm4s.agent.graph

import org.llm4s.error.LLMError
import upickle.default.ReadWriter

/**
 * A graph run paused at a superstep boundary: the committed state, the ready frontier, the open
 * join activations and any parked continuations. Advance it with [[CompiledGraph.step]]; answer
 * its interrupts with [[CompiledGraph.resume]]; persist it with [[CompiledGraph.snapshot]].
 */
final class Execution private[graph] (
  private[graph] val owner: GraphOwner,
  val superstep: Int,
  val state: ThreadState,
  private[graph] val frontier: Vector[Task],
  private[graph] val staticArrivals: Map[JoinId, Set[NodeId]],
  private[graph] val dynamicActivations: Vector[DynamicActivation],
  private[graph] val parked: Vector[Parked],
  private[graph] val paused: Boolean
):

  /** No task is ready: the next step completes the run, suspends it, or reports an unsatisfied join. */
  def isQuiescent: Boolean = frontier.isEmpty

  /** A task suspended in the last superstep: the run pauses until a resume. */
  def isPaused: Boolean = paused

  /** The ready tasks, in the order their updates will be applied. */
  def pendingTasks: Vector[(TaskId, NodeId)] = frontier.map(t => t.id -> t.node)

  /** The parked continuations, in the order they were parked. */
  def pendingInterrupts: Vector[InterruptId] = parked.map(_.interrupt)

  private[graph] def copy(
    frontier: Vector[Task] = frontier,
    parked: Vector[Parked] = parked,
    paused: Boolean = paused
  ): Execution =
    new Execution(owner, superstep, state, frontier, staticArrivals, dynamicActivations, parked, paused)

final private[graph] case class JoinSlot(join: JoinId, fanOutTask: TaskId)

/** The suspended task a continuation stands in for: its join arrivals are made in this name. */
final private[graph] case class Origin(task: TaskId, node: NodeId)

final private[graph] case class Task(
  id: TaskId,
  node: NodeId,
  input: Any,
  slot: Option[JoinSlot],
  origin: Option[Origin] = None
):
  /** The task id a dynamic join counts this task's completion as. */
  def arrivalId: TaskId = origin.fold(id)(_.task)

  /** The node a static join counts this task's completion as. */
  def arrivalNode: NodeId = origin.fold(node)(_.node)

/** A suspended task's continuation, waiting for an answer. */
final private[graph] case class Parked(
  interrupt: InterruptId,
  resumeNode: NodeId,
  question: Any,
  origin: Origin,
  slot: Option[JoinSlot]
)

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

  /**
   * The run paused with parked continuations; `state` and every sibling's updates are committed.
   * Answer any non-empty subset of `interrupts` to continue. `execution` resumes in memory with
   * [[CompiledGraph.resume]]; a durable thread resumes with [[GraphRuntime.resume]].
   */
  case Suspended(state: ThreadState, interrupts: Vector[PendingInterrupt], execution: Execution)
      extends RunResult[Nothing]

  /** `state` is the last committed state; the failing superstep's updates are not applied. */
  case Failed(state: ThreadState, error: LLMError) extends RunResult[Nothing]

/** The outcome of one superstep. */
enum Step[+O]:
  case Next(execution: Execution)     extends Step[Nothing]
  case Done[+O](result: RunResult[O]) extends Step[O]

/**
 * A serializable picture of an [[Execution]] - data only, no closures or codecs. Node inputs,
 * questions and state values are encoded with the codecs of the graph that wrote them, and
 * decoded and checked against the graph that restores them.
 *
 * Each value records its codec's version and is migrated on restore; the snapshot itself is
 * versioned by the [[Checkpoint]] that carries it.
 */
final case class GraphSnapshot(
  graphId: String,
  graphVersion: String,
  fingerprint: String,
  superstep: Int,
  state: Map[String, VersionedJson],
  frontier: Vector[GraphSnapshot.PendingTask],
  staticJoins: Vector[GraphSnapshot.StaticArrivals],
  dynamicJoins: Vector[GraphSnapshot.Activation],
  parked: Vector[GraphSnapshot.ParkedContinuation],
  paused: Boolean
) derives ReadWriter

object GraphSnapshot:
  final case class PendingTask(
    taskId: String,
    nodeId: String,
    input: VersionedJson,
    joinId: Option[String],
    fanOutTask: Option[String],
    originTask: Option[String],
    originNode: Option[String]
  ) derives ReadWriter

  final case class StaticArrivals(joinId: String, arrived: Vector[String]) derives ReadWriter

  final case class Activation(joinId: String, fanOutTask: String, expected: Vector[String], arrived: Vector[String])
      derives ReadWriter

  final case class ParkedContinuation(
    interruptId: String,
    resumeNode: String,
    question: VersionedJson,
    originTask: String,
    originNode: String,
    joinId: Option[String],
    fanOutTask: Option[String]
  ) derives ReadWriter
