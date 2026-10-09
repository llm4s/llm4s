package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import scala.concurrent.duration.FiniteDuration

/**
 * Limits on one run; each `start`, `recover` or `resume` brings its own. Every value is validated
 * on the way in: `apply` and the `with*` setters throw `IllegalArgumentException` for a
 * non-positive one, and [[RunBudgets.of]] returns it as a `ValidationError`.
 */
final case class RunBudgets private (maxSupersteps: Int, timeout: Option[FiniteDuration], maxConcurrency: Int):
  def withMaxSupersteps(n: Int): RunBudgets              = RunBudgets(n, timeout, maxConcurrency)
  def withTimeout(t: FiniteDuration): RunBudgets         = RunBudgets(maxSupersteps, Some(t), maxConcurrency)
  def withTimeout(t: Option[FiniteDuration]): RunBudgets = RunBudgets(maxSupersteps, t, maxConcurrency)
  def withMaxConcurrency(n: Int): RunBudgets             = RunBudgets(maxSupersteps, timeout, n)

object RunBudgets:
  private def problems(maxSupersteps: Int, timeout: Option[FiniteDuration], maxConcurrency: Int): List[String] =
    List(
      Option.when(maxSupersteps <= 0)(s"maxSupersteps must be positive, was $maxSupersteps"),
      timeout.filter(_.length <= 0).map(t => s"timeout must be positive, was $t"),
      Option.when(maxConcurrency <= 0)(s"maxConcurrency must be positive, was $maxConcurrency")
    ).flatten

  /** Throws `IllegalArgumentException` for a non-positive value; use [[of]] for untrusted input. */
  def apply(maxSupersteps: Int = 1000, timeout: Option[FiniteDuration] = None, maxConcurrency: Int = 16): RunBudgets =
    val found = problems(maxSupersteps, timeout, maxConcurrency)
    require(found.isEmpty, found.mkString("; "))
    new RunBudgets(maxSupersteps, timeout, maxConcurrency)

  def of(
    maxSupersteps: Int = 1000,
    timeout: Option[FiniteDuration] = None,
    maxConcurrency: Int = 16
  ): Result[RunBudgets] =
    problems(maxSupersteps, timeout, maxConcurrency) match
      case Nil   => Right(new RunBudgets(maxSupersteps, timeout, maxConcurrency))
      case found => Left(ValidationError("budgets", found))

  val default: RunBudgets = apply()

/**
 * The identity and limits of one run. `RunConfig()` is evaluated per call, so each gets a fresh
 * [[RunId]]. The tenant is part of the thread's identity: it is recorded on every checkpoint, and
 * admission refuses a run whose `tenantId` differs from the latest checkpoint's
 * ([[GraphError.TenantMismatch]]; `None` and `Some` differ). The principal is recorded on the run's
 * `RunStarted`, `RunRecovered` and `RunResumed` events and is never checked.
 *
 * `interruptBefore` and `interruptAfter` are the run's static breakpoints ([[BreakpointPhase]]): a
 * task of a node in `interruptBefore` is held before it runs, and one of a node in `interruptAfter`
 * after it ran, each as an interrupt of its own that [[GraphRuntime.resume]] answers with
 * [[Breakpoint.proceed]]. They belong to the run, like its budgets: a resume or recover brings its
 * own, and a continuation already past a breakpoint is not held by it again. Admission refuses a
 * node the graph does not have with a `ValidationError`.
 */
final case class RunConfig private (
  runId: RunId,
  tenantId: Option[TenantId],
  principal: Option[Principal],
  budgets: RunBudgets,
  metadata: Map[String, String],
  interruptBefore: Set[NodeId],
  interruptAfter: Set[NodeId]
):
  def withRunId(id: RunId): RunConfig                 = copy(runId = id)
  def withTenantId(t: TenantId): RunConfig            = copy(tenantId = Some(t))
  def withTenantId(t: Option[TenantId]): RunConfig    = copy(tenantId = t)
  def withPrincipal(p: Principal): RunConfig          = copy(principal = Some(p))
  def withPrincipal(p: Option[Principal]): RunConfig  = copy(principal = p)
  def withBudgets(b: RunBudgets): RunConfig           = copy(budgets = b)
  def withMetadata(m: Map[String, String]): RunConfig = copy(metadata = m)

  /** Holds every task of these nodes before it runs, replacing the nodes set before. */
  def withInterruptBefore(nodes: Set[NodeId]): RunConfig = copy(interruptBefore = nodes)

  /** Holds every task of these nodes after it ran, replacing the nodes set before. */
  def withInterruptAfter(nodes: Set[NodeId]): RunConfig = copy(interruptAfter = nodes)

object RunConfig:
  def apply(
    runId: RunId = RunId.random(),
    tenantId: Option[TenantId] = None,
    principal: Option[Principal] = None,
    budgets: RunBudgets = RunBudgets.default,
    metadata: Map[String, String] = Map.empty,
    interruptBefore: Set[NodeId] = Set.empty,
    interruptAfter: Set[NodeId] = Set.empty
  ): RunConfig = new RunConfig(runId, tenantId, principal, budgets, metadata, interruptBefore, interruptAfter)

/** Where a task runs. `checkpointId` is the checkpoint whose frontier it belongs to; `""` outside a [[GraphRuntime]]. */
final case class RunPosition(
  threadId: ThreadId,
  runId: RunId,
  checkpointId: String,
  taskId: TaskId,
  nodeId: NodeId,
  superstep: Int
)

/**
 * What a running task knows: the run's [[RunConfig]], its own [[RunPosition]], and its event channels.
 *
 * `emit` records a durable custom event: it is committed with this task's result, given a
 * per-thread sequence number in that commit, delivered only after the commit and replayed to
 * later subscribers. It is discarded if the task fails. `progress` is live-only, for token deltas
 * and similar high-volume progress, and carries a name and version like `emit`: delivered at once to current subscribers, never persisted or
 * replayed. Outside a [[GraphRuntime]] both are no-ops.
 */
final class RunContext private[graph] (
  val config: RunConfig,
  val position: RunPosition,
  sink: NodeEventSink,
  /**
   * Answers to middleware questions, by asking middleware id, as (question, answer) JSON: set when a
   * hook runs again after its questions were answered; see `AgentMiddleware.Asking`.
   */
  private[agent] val answers: Map[String, (ujson.Value, ujson.Value)] = Map.empty
):
  /** This context with `next` as the answers its hooks see. */
  private[agent] def withAnswers(next: Map[String, (ujson.Value, ujson.Value)]): RunContext =
    new RunContext(config, position, sink, next)

  /** Records a durable custom event; `payload` is snapshotted at the call, so the node may reuse it. */
  def emit(name: String, version: Int, payload: ujson.Value): Unit = sink.custom(name, version, payload)

  /** Offers live progress named `name`, version `version`; `payload` is snapshotted at the call. */
  def progress(name: String, version: Int, payload: ujson.Value): Unit = sink.progress(name, version, payload)

  /** The task thread's interrupt flag, read without clearing it. */
  def isCancelled: Boolean = Thread.currentThread().isInterrupted

  /** Forgets the durable events a failed attempt emitted, before the node is run again. */
  private[graph] def discardAttempt(): Unit = sink.discardCustom()
