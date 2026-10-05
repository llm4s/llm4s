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
 */
final case class RunConfig private (
  runId: RunId,
  tenantId: Option[TenantId],
  principal: Option[Principal],
  budgets: RunBudgets,
  metadata: Map[String, String]
):
  def withRunId(id: RunId): RunConfig                 = copy(runId = id)
  def withTenantId(t: TenantId): RunConfig            = copy(tenantId = Some(t))
  def withTenantId(t: Option[TenantId]): RunConfig    = copy(tenantId = t)
  def withPrincipal(p: Principal): RunConfig          = copy(principal = Some(p))
  def withPrincipal(p: Option[Principal]): RunConfig  = copy(principal = p)
  def withBudgets(b: RunBudgets): RunConfig           = copy(budgets = b)
  def withMetadata(m: Map[String, String]): RunConfig = copy(metadata = m)

object RunConfig:
  def apply(
    runId: RunId = RunId.random(),
    tenantId: Option[TenantId] = None,
    principal: Option[Principal] = None,
    budgets: RunBudgets = RunBudgets.default,
    metadata: Map[String, String] = Map.empty
  ): RunConfig = new RunConfig(runId, tenantId, principal, budgets, metadata)

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
 * and similar high-volume progress: delivered at once to current subscribers, never persisted or
 * replayed. Outside a [[GraphRuntime]] both are no-ops.
 */
final class RunContext private[graph] (
  val config: RunConfig,
  val position: RunPosition,
  sink: NodeEventSink
):
  /** Records a durable custom event; `payload` is snapshotted at the call, so the node may reuse it. */
  def emit(name: String, version: Int, payload: ujson.Value): Unit = sink.custom(name, version, payload)

  /** Offers live progress; `payload` is snapshotted at the call, so the node may reuse it. */
  def progress(payload: ujson.Value): Unit = sink.progress(payload)

  /** The task thread's interrupt flag, read without clearing it. */
  def isCancelled: Boolean = Thread.currentThread().isInterrupted

  /** Forgets the durable events a failed attempt emitted, before the node is run again. */
  private[graph] def discardAttempt(): Unit = sink.discardCustom()
