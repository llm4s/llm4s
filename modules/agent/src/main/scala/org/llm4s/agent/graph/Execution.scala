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
  private[graph] val paused: Boolean,
  /**
   * Effect records of guards run by [[CompiledGraph.step]] outside a runtime (design §4.24): carried from
   * each execution to the next, so a continuation resumed in memory replays what its task recorded before
   * it suspended. Inside a [[GraphRuntime]] the thread's store holds them instead. Not part of a snapshot:
   * an execution restored from one starts with none, as a process restarted without a durable store would.
   */
  private[graph] val journal: EffectJournal.Local = EffectJournal.local(),
  /**
   * The epoch of the tasks this execution schedules: the fencing token of the claim whose run schedules them
   * (design §4.24), set when a [[GraphRuntime]] admits the run; `0` outside a runtime. Not part of a snapshot:
   * each task carries its own epoch there.
   */
  private[graph] val epoch: Long = 0L
):

  /** The tasks that can still run from here, as [[GraphSnapshot.liveTasks]] says of a snapshot. */
  private[graph] def liveTasks: Set[(String, Long)] =
    frontier.iterator.flatMap(t => Iterator(t.id.value -> t.epoch, t.arrivalId.value -> t.arrivalEpoch)).toSet ++
      parked.map(p => p.origin.task.value -> p.origin.epoch)

  /**
   * This execution as run `token` schedules it: its epoch is `token`, and so is that of every task created for
   * this run before it was admitted (a new entry task, a resumed continuation). Restored tasks keep theirs.
   */
  private[graph] def stampedBy(token: Long): Execution =
    new Execution(
      owner,
      superstep,
      state,
      frontier.map(t => if t.epoch == Task.Unstamped then t.copy(epoch = token) else t),
      staticArrivals,
      dynamicActivations,
      parked,
      paused,
      journal,
      token
    )

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
    new Execution(owner, superstep, state, frontier, staticArrivals, dynamicActivations, parked, paused, journal, epoch)

  /** This execution with `state` in place of its own, at superstep `at`; everything scheduled stays. */
  private[graph] def edited(state: ThreadState, at: Int): Execution =
    new Execution(owner, at, state, frontier, staticArrivals, dynamicActivations, parked, paused, journal, epoch)

final private[graph] case class JoinSlot(join: JoinId, fanOutTask: TaskId)

/** The suspended task a continuation stands in for: its join arrivals are made in this name. */
final private[graph] case class Origin(task: TaskId, node: NodeId, epoch: Long = 0L)

/**
 * A scheduled task. `passedBefore` is set on the continuation of a task an `interruptBefore` breakpoint
 * held, so the same breakpoint does not hold it again. `replay` is set on the continuation of a task an
 * `interruptAfter` breakpoint held: the task already ran and its update is committed, so the
 * continuation runs nothing and completes with these routes, making the held task's arrivals.
 */
final private[graph] case class Task(
  id: TaskId,
  node: NodeId,
  input: Any,
  slot: Option[JoinSlot],
  origin: Option[Origin] = None,
  passedBefore: Boolean = false,
  replay: Option[List[Route]] = None,
  /**
   * The fencing token of the claim whose run scheduled the task (design §4.24): with the id, the task's
   * identity for its effects. A task planned again by another run - work after the last durable checkpoint
   * under `Async` or `OnExit` - has another epoch, so it never shares replay evidence with the first plan.
   */
  epoch: Long = Task.Unstamped
):
  /** The task id a dynamic join counts this task's completion as. */
  def arrivalId: TaskId = origin.fold(id)(_.task)

  /** The epoch of the task a continuation stands in for, else this task's. */
  def arrivalEpoch: Long = origin.fold(epoch)(_.epoch)

  /** The node a static join counts this task's completion as. */
  def arrivalNode: NodeId = origin.fold(node)(_.node)

/**
 * A suspended task's continuation, waiting for an answer. For a task a static breakpoint holds,
 * `hold` says what continues it, `resumeNode` is the held task's own node and `question` is unused.
 */
private[graph] object Task:
  /** The epoch of a task created before its run was admitted; admission stamps it with the run's token. */
  val Unstamped: Long = -1L

final private[graph] case class Parked(
  interrupt: InterruptId,
  resumeNode: NodeId,
  question: Any,
  origin: Origin,
  slot: Option[JoinSlot],
  hold: Option[Hold] = None
)

/** What a static breakpoint holds: a task before it runs, or a task's routes after it ran. */
private[graph] enum Hold:
  /** The task's input: the continuation runs the node with it, past the breakpoint. */
  case Before(input: Any)

  /** The routes the task returned: the continuation schedules them, making the task's arrivals. */
  case After(routes: List[Route])

  def phase: BreakpointPhase = this match
    case Before(_) => BreakpointPhase.Before
    case After(_)  => BreakpointPhase.After

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
 * versioned by the [[Checkpoint]] that carries it. `shape` is the writing graph's declared structure, which
 * [[CompiledGraph.restore]] compares with its own; `None` on a snapshot written before shapes were recorded (#1934),
 * which is matched by `fingerprint` alone.
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
  paused: Boolean,
  shape: Option[GraphShape] = None
) derives ReadWriter:

  /**
   * The tasks that can still run from this snapshot, by id: the frontier's, and the tasks the
   * frontier's and the parked continuations stand in for. Their effect records are kept; every other
   * task of the thread has finished, or will be planned again ([[Commit.releasesEffect]]).
   */
  def liveTasks: Set[(String, Long)] =
    frontier.iterator
      .flatMap(task =>
        Iterator(task.taskId -> task.epoch) ++ task.originTask.map(_ -> task.originEpoch.getOrElse(task.epoch))
      )
      .toSet ++ parked.map(p => p.originTask -> p.originEpoch)

object GraphSnapshot:
  /**
   * A ready task. `passedBefore` marks the continuation of a task an `interruptBefore` breakpoint held;
   * `replay` holds the routes of a task an `interruptAfter` breakpoint held, whose continuation runs
   * nothing (its `input` is then JSON `null`).
   */
  final case class PendingTask(
    taskId: String,
    nodeId: String,
    input: VersionedJson,
    joinId: Option[String],
    fanOutTask: Option[String],
    originTask: Option[String],
    originNode: Option[String],
    passedBefore: Boolean = false,
    replay: Option[Vector[EncodedRoute]] = None,
    /** The fencing token of the claim that scheduled the task; `0` for a task written before epochs (format 6). */
    epoch: Long = 0L,
    /** The epoch of the task a continuation stands in for. */
    originEpoch: Option[Long] = None
  ) derives ReadWriter

  final case class StaticArrivals(joinId: String, arrived: Vector[String]) derives ReadWriter

  final case class Activation(joinId: String, fanOutTask: String, expected: Vector[String], arrived: Vector[String])
      derives ReadWriter

  /**
   * A parked continuation. For a task a static breakpoint holds, `breakpoint` is its phase and
   * `resumeNode` the held task's node: before it runs, `question` is the task's input; after it ran,
   * `question` is JSON `null` and `heldRoutes` are the routes it returned.
   */
  final case class ParkedContinuation(
    interruptId: String,
    resumeNode: String,
    question: VersionedJson,
    originTask: String,
    originNode: String,
    joinId: Option[String],
    fanOutTask: Option[String],
    breakpoint: Option[BreakpointPhase] = None,
    heldRoutes: Vector[EncodedRoute] = Vector.empty,
    /** The epoch of the task the continuation stands in for; `0` before epochs (format 6). */
    originEpoch: Long = 0L
  ) derives ReadWriter
