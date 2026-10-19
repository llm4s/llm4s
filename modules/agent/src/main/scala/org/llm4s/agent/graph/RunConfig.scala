package org.llm4s.agent.graph

import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ TokenUsage, UsageSummary }
import org.llm4s.types.Result

import java.util.concurrent.atomic.AtomicReference

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
  interruptAfter: Set[NodeId],
  retention: Option[RetentionPolicy],
  childRetention: Int
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

  /**
   * Prunes the thread to `policy` ([[Checkpointer.prune]]) as the run is admitted: once the store has granted its claim
   * and before the run commits anything, so a run refused at admission - a blank query, a busy thread, a pending
   * interrupt, another tenant's thread - leaves the thread unchanged, nothing else writes to the thread while it prunes,
   * and nothing of the run exists yet to be pruned, by any limit, so its own replay is whole. Pruning never removes the
   * latest checkpoint or its pending writes, so what the run continues from is unchanged; earlier runs' checkpoints and
   * events beyond the policy are removed, raising the replay floor for their handles. `RetentionPolicy.keepAll`
   * removes nothing and is skipped. A prune that fails is logged at WARN and the run goes on: retention is
   * housekeeping.
   */
  def withRetention(policy: RetentionPolicy): RunConfig = copy(retention = Some(policy))

  /** [[withRetention]], or no pruning for `None`. */
  def withRetention(policy: Option[RetentionPolicy]): RunConfig = copy(retention = policy)

  /**
   * How many settled (`Completed` or `Failed`) child threads of the run's thread to keep (design 4.41, default
   * [[RunConfig.DefaultChildRetention]]): once the run's terminal checkpoint commits, and at a later `start` on the
   * settled thread, older settled children are deleted, each with everything below it. At that `start` a child an
   * earlier run left `Running` or `Suspended` can never be reattached, so it is first settled
   * `RunOutcome.Failed("abandoned", ...)` and then counts; otherwise one is never deleted, and nothing is at
   * `recover` or `resume`. Throws `IllegalArgumentException` below 0.
   */
  def withChildRetention(n: Int): RunConfig =
    require(n >= 0, s"childRetention must not be negative, was $n")
    copy(childRetention = n)

object RunConfig:
  def apply(
    runId: RunId = RunId.random(),
    tenantId: Option[TenantId] = None,
    principal: Option[Principal] = None,
    budgets: RunBudgets = RunBudgets.default,
    metadata: Map[String, String] = Map.empty,
    interruptBefore: Set[NodeId] = Set.empty,
    interruptAfter: Set[NodeId] = Set.empty,
    retention: Option[RetentionPolicy] = None,
    childRetention: Int = DefaultChildRetention
  ): RunConfig =
    require(childRetention >= 0, s"childRetention must not be negative, was $childRetention")
    new RunConfig(
      runId,
      tenantId,
      principal,
      budgets,
      metadata,
      interruptBefore,
      interruptAfter,
      retention,
      childRetention
    )

  /** Settled child threads a thread keeps unless its run says otherwise ([[RunConfig.withChildRetention]]). */
  val DefaultChildRetention: Int = 64

  /** A config keeping [[DefaultChildRetention]] children, as before `childRetention` existed. */
  def apply(
    runId: RunId,
    tenantId: Option[TenantId],
    principal: Option[Principal],
    budgets: RunBudgets,
    metadata: Map[String, String],
    interruptBefore: Set[NodeId],
    interruptAfter: Set[NodeId],
    retention: Option[RetentionPolicy]
  ): RunConfig =
    new RunConfig(
      runId,
      tenantId,
      principal,
      budgets,
      metadata,
      interruptBefore,
      interruptAfter,
      retention,
      DefaultChildRetention
    )

  /** A config without retention, as before `retention` existed. */
  def apply(
    runId: RunId,
    tenantId: Option[TenantId],
    principal: Option[Principal],
    budgets: RunBudgets,
    metadata: Map[String, String],
    interruptBefore: Set[NodeId],
    interruptAfter: Set[NodeId]
  ): RunConfig =
    new RunConfig(
      runId,
      tenantId,
      principal,
      budgets,
      metadata,
      interruptBefore,
      interruptAfter,
      None,
      DefaultChildRetention
    )

/**
 * Where a task runs. `checkpointId` is the checkpoint whose frontier it belongs to; `""` outside a
 * [[GraphRuntime]]. `fencingToken` is the token of the run's claim on the thread ([[RunClaim]]),
 * `None` outside a runtime: a node that writes to an external system able to fence can pass it on,
 * so that system too refuses a run that has lost its claim.
 */
final case class RunPosition private (
  threadId: ThreadId,
  runId: RunId,
  checkpointId: String,
  taskId: TaskId,
  nodeId: NodeId,
  superstep: Int,
  fencingToken: Option[FencingToken]
):
  def withThreadId(t: ThreadId): RunPosition                 = copy(threadId = t)
  def withRunId(r: RunId): RunPosition                       = copy(runId = r)
  def withCheckpointId(c: String): RunPosition               = copy(checkpointId = c)
  def withTaskId(t: TaskId): RunPosition                     = copy(taskId = t)
  def withNodeId(n: NodeId): RunPosition                     = copy(nodeId = n)
  def withSuperstep(s: Int): RunPosition                     = copy(superstep = s)
  def withFencingToken(t: FencingToken): RunPosition         = copy(fencingToken = Some(t))
  def withFencingToken(t: Option[FencingToken]): RunPosition = copy(fencingToken = t)

object RunPosition:
  def apply(
    threadId: ThreadId,
    runId: RunId,
    checkpointId: String,
    taskId: TaskId,
    nodeId: NodeId,
    superstep: Int,
    fencingToken: Option[FencingToken] = None
  ): RunPosition = new RunPosition(threadId, runId, checkpointId, taskId, nodeId, superstep, fencingToken)

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
  private[agent] val answers: Map[String, (ujson.Value, ujson.Value)] = Map.empty,
  /** The model usage this task's hooks and model calls recorded; see `ModelContext`. */
  private[agent] val usage: TaskUsage = new TaskUsage,
  /** Where this task's [[ReplayGuard]]s keep effect records: the thread's store in a runtime, memory outside one. */
  private[graph] val journal: EffectJournal = EffectJournal.local(),
  /** The task effect records belong to: the task a continuation stands in for, else this one (design §4.24). */
  owner: Option[(TaskId, String)] = None,
  /** A resume continuation's identity, kept with its task ([[CompiledGraph.resumeDigest]]). */
  private[agent] val resumeDigest: Option[String] = None
):
  /** This context with `next` as the answers its hooks see, recording usage in the same task's accumulator. */
  private[agent] def withAnswers(next: Map[String, (ujson.Value, ujson.Value)]): RunContext =
    new RunContext(config, position, sink, next, usage, journal, owner, resumeDigest)

  /**
   * An object unique to this run incarnation: the same for every task of one run of a runtime (or one execution of a
   * graph outside one), and never for two runs - though two runtimes over different stores can issue one thread the
   * same run id and fencing token. Compared by reference; a cache of something read once per run keys on it.
   */
  private[agent] def incarnation: AnyRef = journal

  /** The task this task's effect records belong to ([[Commit.releasesEffect]]). */
  private[graph] def effectOwner: TaskId = owner.fold(position.taskId)(_._1)

  /** The lineage of the task this task's effect records belong to (design §4.24). */
  private[graph] def effectLineage: String = owner.fold("")(_._2)

  /**
   * This task's key: the SHA-256 of the thread and the id of the logical task (design §4.24) - for a
   * continuation, the task it stands in for, so a hook that runs again after an answer, an approval or a
   * breakpoint keeps its key. It is the same in every run of the task: a retry, `recover`, `recover`
   * after `updateState`, and a resumed continuation. The base of a hook's [[effectKey]]s, and the key a
   * hook gives an external system. It identifies the task among the thread's live tasks, not for all
   * time: after `deleteThread`, or work after the last durable checkpoint planned again under
   * `Async`/`OnExit`, a task id can name new work.
   */
  lazy val taskKey: IdempotencyKey = IdempotencyKey.ofTask(position.threadId, effectOwner, effectLineage)

  /**
   * The key of one external effect of this task - `operation`, such as `memory.record`, on what
   * `discriminator` names - for [[ReplayGuard]]. A tool uses `ToolContext.effectKey`, whose base is its
   * call's [[IdempotencyKey]]. Give each effect of a task its own operation or discriminator, and pass
   * a set (memory ids) sorted.
   */
  def effectKey(operation: String, discriminator: String*): EffectKey =
    EffectKey.derive(position.threadId, taskKey, operation, discriminator)

  /** The key of an effect under `base`, by this task's thread. */
  private[graph] def effectKeyUnder(base: IdempotencyKey, operation: String, discriminator: Seq[String]): EffectKey =
    EffectKey.derive(position.threadId, base, operation, discriminator)

  /** Records a durable custom event; `payload` is snapshotted at the call, so the node may reuse it. */
  def emit(name: String, version: Int, payload: ujson.Value): Unit = sink.custom(name, version, payload)

  /** Offers live progress named `name`, version `version`; `payload` is snapshotted at the call. */
  def progress(name: String, version: Int, payload: ujson.Value): Unit = sink.progress(name, version, payload)

  /** The task thread's interrupt flag, read without clearing it. */
  def isCancelled: Boolean = Thread.currentThread().isInterrupted

  /** Forgets the durable events a failed attempt emitted, before the node is run again. */
  private[graph] def discardAttempt(): Unit = sink.discardCustom()

/**
 * The model usage one task recorded, from every thread the task runs hooks on. A task that commits adds it to its
 * own update; the runtime puts a failed task's on the run's `RunFailed` (design §4.22).
 */
final private[agent] class TaskUsage:
  private val recorded = new AtomicReference(UsageSummary())

  def add(model: String, usage: TokenUsage, cost: Option[Double]): Unit =
    recorded.updateAndGet(_.add(model, usage, cost)): Unit

  def summary: UsageSummary = recorded.get

  def isEmpty: Boolean = recorded.get.requestCount == 0
