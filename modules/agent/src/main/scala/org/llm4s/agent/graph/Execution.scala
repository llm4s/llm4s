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
  private[graph] val journal: EffectJournal.Local = EffectJournal.local()
):

  /** The tasks that can still run from here, as [[GraphSnapshot.liveTasks]] says of a snapshot. */
  private[graph] def liveTasks: Set[(String, String)] =
    frontier.iterator.flatMap(t => Iterator(t.id.value -> t.lineage, t.arrivalId.value -> t.arrivalLineage)).toSet ++
      parked.map(p => p.origin.task.value -> p.origin.lineage)

  /**
   * This execution as run `token` admits it: every task created for the run before it was admitted - a new
   * entry task, a resumed continuation - gets a root lineage of the run's claim (design §4.24). The claim's
   * checkpoint holds them, so they keep it in every later run. Restored and scheduled tasks keep theirs.
   */
  private[graph] def stampedBy(token: Long): Execution =
    new Execution(
      owner,
      superstep,
      state,
      frontier.map { t =>
        val origin = t.origin.map(o =>
          if o.lineage == Task.Unstamped then o.copy(lineage = Task.rootLineage(token, o.task)) else o
        )
        val lineage = if t.lineage == Task.Unstamped then Task.rootLineage(token, t.id) else t.lineage
        t.copy(lineage = lineage, origin = origin)
      },
      staticArrivals,
      dynamicActivations,
      parked.map { p =>
        if p.origin.lineage == Task.Unstamped then
          p.copy(origin = p.origin.copy(lineage = Task.rootLineage(token, p.origin.task)))
        else p
      },
      paused,
      journal
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
    new Execution(owner, superstep, state, frontier, staticArrivals, dynamicActivations, parked, paused, journal)

  /** This execution with `state` in place of its own, at superstep `at`; everything scheduled stays. */
  private[graph] def edited(state: ThreadState, at: Int): Execution =
    new Execution(owner, at, state, frontier, staticArrivals, dynamicActivations, parked, paused, journal)

final private[graph] case class JoinSlot(join: JoinId, fanOutTask: TaskId)

/** The suspended task a continuation stands in for: its join arrivals are made in this name. */
final private[graph] case class Origin(task: TaskId, node: NodeId, lineage: String = "")

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
   * With the id, the task's identity for its effects (design §4.24): a digest of what scheduled it - the
   * tasks of the superstep before, their ids, lineages and results - or, for a task created before its run
   * was admitted, of the run's claim. Work planned again after a takeover from the same results is the same
   * task, so it replays the first plan's evidence; work planned from other results is another task.
   */
  lineage: String = Task.Unstamped
):
  /** The task id a dynamic join counts this task's completion as. */
  def arrivalId: TaskId = origin.fold(id)(_.task)

  /** The lineage of the task a continuation stands in for, else this task's. */
  def arrivalLineage: String = origin.fold(lineage)(_.lineage)

  /** The node a static join counts this task's completion as. */
  def arrivalNode: NodeId = origin.fold(node)(_.node)

/**
 * A suspended task's continuation, waiting for an answer. For a task a static breakpoint holds,
 * `hold` says what continues it, `resumeNode` is the held task's own node and `question` is unused.
 */
private[graph] object Task:
  /**
   * The lineage of a task created before its run was admitted, and of a task of a format-6 checkpoint, which has
   * none (format 6 never shipped); admission stamps it with a root lineage of its claim ([[rootLineage]]).
   */
  val Unstamped: String = ""

  /** The lineage of task `id` created for the run of claim `token` before it was admitted. */
  def rootLineage(token: Long, id: TaskId): String =
    KeyDigest.of(Seq("llm4s-lineage-root-v1", token.toString, id.value))

  /**
   * The lineage of task `id` scheduled by a superstep whose tasks and their results digest to `superstep`
   * ([[CompiledGraph.schedule]]).
   */
  def scheduledLineage(superstep: String, id: TaskId): String =
    KeyDigest.of(Seq("llm4s-lineage-v1", superstep, id.value))

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
  def liveTasks: Set[(String, String)] =
    frontier.iterator
      .flatMap(task =>
        Iterator(task.taskId -> task.lineage) ++ task.originTask.map(_ -> task.originLineage.getOrElse(task.lineage))
      )
      .toSet ++ parked.map(p => p.originTask -> p.originLineage)

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
    /** The task's lineage (design §4.24); empty for a task written before lineages (format 6). */
    lineage: String = "",
    /** The lineage of the task a continuation stands in for. */
    originLineage: Option[String] = None
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
    /** The lineage of the task the continuation stands in for; empty before lineages (format 6). */
    originLineage: String = ""
  ) derives ReadWriter

/** A result decoded from a pending write, with the digest its successors' lineage is computed from. */
final private[graph] case class ReusedWrite(result: TaskResult, digest: String)
