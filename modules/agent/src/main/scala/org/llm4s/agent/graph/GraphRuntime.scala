package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ ExecutorService, Executors, TimeUnit }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{ Try, Using }

/**
 * When a run's checkpoints and events become durable. In every mode a durable event is delivered
 * only after the commit that numbered it, so no subscriber sees a sequence number that a crash
 * could later reuse. In every mode a run claims its thread with a synchronous commit before it
 * runs anything, and makes everything durable before it returns - so a returned
 * [[RunResult.Suspended]] has been persisted and can be resumed by another process.
 */
enum Durability:
  /** Each task result and superstep is committed before the run moves on. */
  case Sync

  /**
   * Commits are queued and written in order on a background writer while the run continues.
   * After a crash, the work since the last durable checkpoint is redone by `recover`. The run
   * waits for the queue before returning, and fails if any queued commit failed.
   */
  case Async

  /**
   * After the claim, nothing is written until the run ends; then the last checkpoint, its pending
   * writes and every buffered event are committed at once. A crash loses the run's progress.
   */
  case OnExit

/** Runs compiled graphs on durable threads; see [[GraphRuntime]]. */
object GraphRuntime:
  private val logger = LoggerFactory.getLogger(classOf[GraphRuntime])

  /** A runtime over a new [[InMemoryCheckpointer]], whose claims expire by the system clock. */
  def inMemory(clock: Clock = Clock.systemUTC()): GraphRuntime = new GraphRuntime(new InMemoryCheckpointer(), clock)

/**
 * Runs compiled graphs on durable threads.
 *
 * A thread's latest checkpoint says what may happen next:
 *
 *  - none, `Completed` or `Failed`: `start` runs the graph with a new input (on a finished thread, over its
 *    committed state). `Failed` is a run that ended in a failure that is its outcome, not an interruption:
 *    a node returned [[NodeResult.Block]], as a guardrail does. The thread is usable and `recover` has
 *    nothing to continue;
 *  - `Running` - work was scheduled when the last run stopped: `recover` continues it with no new
 *    input, reusing every pending write so completed tasks are not run again, and running failed
 *    or unstarted tasks again, each with its node's full [[RetryPolicy]];
 *  - `Suspended`: `resume` answers any non-empty subset of the parked interrupts; unanswered ones
 *    stay parked, and the run suspends again if nothing else can proceed.
 *
 * Any other call is refused ([[GraphError.IncompleteRun]], [[GraphError.PendingInterrupts]],
 * [[GraphError.NothingToRecover]], [[GraphError.NotSuspended]]) without changing the thread. A
 * thread belongs to the tenant on its latest checkpoint, and a call with another `tenantId` is
 * refused with [[GraphError.TenantMismatch]] before anything else is reported - the thread's
 * status, or that a run is live on it - and the error names only the caller's tenant, so it
 * learns nothing about the thread.
 *
 * Each call is a new run, and claims the thread in two steps before it runs anything. First it
 * asks the store for a [[RunClaim]]: a lease with an expiry and a [[FencingToken]], which the run
 * renews while it executes (see [[ClaimPolicy]]) and releases when it ends. While another run's
 * claim is live - in this runtime or in any other runtime or process sharing the store - the call
 * fails with [[GraphError.ThreadBusy]] naming that run, so `recover` elsewhere refuses a live run
 * and takes over a dead one only once its claim has expired. Then it commits a checkpoint whose
 * parent is the latest it read; if another run advanced the thread in between, it fails with
 * `ThreadBusy` too. Either way its input or answers are neither accepted nor discarded. Every
 * commit of the run carries its claim's token, and the store refuses one whose claim was taken
 * over ([[GraphError.StaleClaim]]), so a run that lost its claim - a process paused past `ttl` -
 * cannot record anything after its successor started: it fails with
 * [[GraphError.CheckpointWriteFailed]]. A call on a thread whose run is still executing in this
 * runtime fails with `ThreadBusy` before reading the thread at all.
 *
 * Admission - the checks above, restoring the thread and committing the claim - runs on the
 * caller's thread and never throws: a `Left` means no run exists, and the thread is free again. A
 * throwable from the store, the clock or the graph during admission is [[GraphError.RunCrashed]],
 * and an interrupt is `CancelledError` with the flag set. The run itself executes on a virtual
 * thread owned by the runtime, named `llm4s-run-<threadId>`, behind the returned [[RunHandle]];
 * the thread stays busy until that run thread exits. An unexpected throwable escaping the run ends
 * it with [[GraphError.RunCrashed]].
 *
 * [[RunHandle.cancel]] cancels the run by interrupting its run thread: every task is interrupted
 * and joined, tasks that finished first keep their pending writes, an interrupted task records
 * nothing, and the run ends `Failed` with [[GraphError.Cancelled]] - naming the last checkpoint,
 * which a [[RunEvent.RunCancelled]] event is committed against. The checkpoint stays `Running`, so
 * `recover` continues the run. A cancel that interrupts a superstep's commit ends the run the same
 * way even if the store reports that commit as failed (as a JDBC store may, rather than throwing
 * `InterruptedException`): the run is cancelled, not failed by its store, and the error names the
 * last checkpoint that was durable. Once the run has begun committing its outcome - its completed
 * or suspended checkpoint, or the `RunFailed` of a failed run - a cancel is ignored - it sends no interrupt, so that commit is not disturbed - and the
 * run ends with its outcome. Interrupting a thread blocked in [[RunHandle.await]] does not cancel
 * the run.
 *
 * A run whose `RunBudgets.timeout` is set has a deadline, fixed when its claim commits. When it
 * passes, the run stops the same way, but ends with [[GraphError.DeadlineExceeded]] and commits
 * [[RunEvent.RunTimedOut]]. The first of cancel and expiry to be recorded wins, so a run reports
 * exactly one. `DeadlineExceeded` is recoverable: `recover` with a new budget continues the run.
 *
 * `subscribe` replays a thread's committed events after a sequence number and then delivers new
 * ones as their commits succeed, in ascending order with no gaps or duplicates, followed by live
 * progress as it happens. Each subscription has its own dispatcher thread and a queue of
 * `capacity` events, so a slow listener never holds up a run: one that falls behind by more than
 * `capacity` durable events is disconnected ([[DisconnectReason.Lagging]]), and live events that
 * do not fit are dropped and counted ([[StreamEvent.LiveGap]]). See [[EventHub]]. Live events are
 * never replayed, but a subscription receives every one sent after `subscribe` returns, also while it
 * is still replaying: so subscribing and then calling `start` loses none of the run's. One made after
 * `start` returns can miss the run's first live events; an [[Observer]] passed to `start`, `recover`
 * or `resume` is subscribed during admission, before the claim commits, and so sees every event of
 * the run ([[RunHandle.observation]]).
 */
final class GraphRuntime(
  checkpointer: Checkpointer,
  clock: Clock = Clock.systemUTC(),
  claims: ClaimPolicy = ClaimPolicy.default
):

  /** For callers that cannot use default arguments (Java, Kotlin): [[ClaimPolicy.default]]. */
  def this(checkpointer: Checkpointer, clock: Clock) = this(checkpointer, clock, ClaimPolicy.default)

  /** For callers that cannot use default arguments (Java, Kotlin): the system clock and [[ClaimPolicy.default]]. */
  def this(checkpointer: Checkpointer) = this(checkpointer, Clock.systemUTC(), ClaimPolicy.default)

  private val hub        = EventHub(checkpointer)
  private val commitLock = new java.util.concurrent.locks.ReentrantLock()

  /**
   * Threads with a run admitting or executing in this runtime, each with that run's tenant, so a
   * caller from another tenant is refused even before the thread's first checkpoint exists, and its
   * run id, the holder `ThreadBusy` names; guarded by `activeLock`.
   */
  private val active     = mutable.Map.empty[String, (Option[String], RunId)]
  private val activeLock = new java.util.concurrent.locks.ReentrantLock()

  /**
   * Replays events with `seq > afterSeq`, then delivers new events until cancelled or
   * disconnected. The subscription is scoped to the thread, so it delivers every later run on it,
   * and its dispatcher (a virtual thread, parked while idle) lives until [[Subscription.cancel]];
   * see there for how cancel waits on a running listener. Only commits made through this runtime are
   * delivered live; another runtime or process sharing the checkpointer is seen only by subscribing
   * again, which replays the log (store-level change notification is Stage 2). Returns without
   * replaying: replay runs on the subscription's dispatcher thread, the only thread `listener` is
   * called on, and a failed replay ends it with [[DisconnectReason.ReplayFailed]]. Joining the event
   * hub takes its lock, which another subscription to any thread holds for its final catch-up read
   * of the store, so `subscribe` can wait behind that one read. The subscription receives every live
   * event sent after this returns: one sent while it replays is held - up to `capacity`, the rest
   * dropped and counted as a [[StreamEvent.LiveGap]] - and delivered after the durable events
   * committed before it and before those committed after it, as live events are once the replay is
   * done. A listener that throws is disconnected
   * ([[DisconnectReason.ListenerFailed]]). `capacity` bounds durable and live events separately,
   * so live events never crowd out durable ones, and must be at least two: a live event is queued
   * only while two live slots are free, one being reserved for the [[StreamEvent.LiveGap]] marker
   * that precedes it, so with one slot no live event could ever be accepted.
   */
  def subscribe(threadId: ThreadId, afterSeq: Long = 0L, capacity: Int = 1024)(
    listener: StreamEvent => Unit
  ): Result[Subscription] = dispatched(threadId, afterSeq, capacity, listener)

  /** [[subscribe]], as the hub's subscription, which a run's handle can give its end-of-run barrier. */
  private def dispatched(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit
  ): Result[Dispatched] =
    if capacity < 2 then
      Left(ValidationError("capacity", s"must be at least 2 (one slot is reserved for a LiveGap), was $capacity"))
    else hub.subscribe(threadId, afterSeq, capacity, listener)

  /** How many subscriptions to `threadId` are in the event hub's live set; for tests. */
  private[llm4s] def liveSubscriptions(threadId: ThreadId): Int = hub.liveCount(threadId)

  /** Starts a run on `threadId` with `input`; see the class description. */
  def start[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    config: RunConfig = RunConfig(),
    durability: Durability = Durability.Sync,
    observer: Option[Observer] = None
  ): Result[RunHandle[O]] = startOn(threadId, graph, input, config, durability, None, observer)

  /**
   * Starts a run on `threadId` only if the thread is new: an existing thread of the caller's tenant
   * is refused with `existing`, no run started. Checked during admission, under the thread's
   * exclusivity and against the claim's parent checkpoint, so no other run can create the thread
   * between the check and the claim. Another tenant's thread is still `TenantMismatch` first.
   */
  private[agent] def startNew[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    config: RunConfig,
    existing: => LLMError,
    observer: Option[Observer] = None
  ): Result[RunHandle[O]] = startOn(threadId, graph, input, config, Durability.Sync, Some(() => existing), observer)

  private def startOn[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    config: RunConfig,
    durability: Durability,
    newOnly: Option[() => LLMError],
    observer: Option[Observer]
  ): Result[RunHandle[O]] = exclusively(threadId, config, observer) { signal =>
    checkpointer.latest(threadId).flatMap {
      case None =>
        newRun(graph, threadId, config, durability, 0, signal).admit(graph.start(input), None, started(config))
      case Some(stored) =>
        checkTenant(threadId, stored, config).flatMap { _ =>
          newOnly match
            case Some(refusal) => Left(refusal())
            case None =>
              stored.checkpoint.status match
                case CheckpointStatus.Running   => Left(GraphError.IncompleteRun(threadId.value, stored.checkpoint.id))
                case CheckpointStatus.Suspended => Left(pendingInterrupts(threadId, stored))
                case CheckpointStatus.Completed | CheckpointStatus.Failed =>
                  graph.restore(stored.checkpoint.snapshot).flatMap { done =>
                    newRun(graph, threadId, config, durability, done.superstep, signal)
                      .admit(
                        graph.startAt(done.superstep, done.state, input),
                        Some(stored.checkpoint.id),
                        started(config)
                      )
                  }
        }
    }
  }

  /** Continues the incomplete execution on `threadId`; see the class description. */
  def recover[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    config: RunConfig = RunConfig(),
    durability: Durability = Durability.Sync,
    observer: Option[Observer] = None
  ): Result[RunHandle[O]] = exclusively(threadId, config, observer) { signal =>
    checkpointer.latest(threadId).flatMap {
      case None => Left(GraphError.NothingToRecover(threadId.value))
      case Some(stored) =>
        checkTenant(threadId, stored, config).flatMap { _ =>
          stored.checkpoint.status match
            case CheckpointStatus.Running =>
              for
                execution <- graph.restore(stored.checkpoint.snapshot)
                reused    <- reusableWrites(graph, execution, stored)
                run <- newRun(graph, threadId, config, durability, execution.superstep, signal).admit(
                  execution,
                  Some(stored.checkpoint.id),
                  RunEvent
                    .RunRecovered(stored.checkpoint.id, config.tenantId.map(_.value), config.principal.map(_.value)),
                  stored.pendingWrites,
                  reused
                )
              yield run
            case CheckpointStatus.Suspended => Left(pendingInterrupts(threadId, stored))
            case CheckpointStatus.Completed | CheckpointStatus.Failed =>
              Left(GraphError.NothingToRecover(threadId.value))
        }
    }
  }

  /**
   * Answers some of the suspended thread's interrupts and continues; see the class description.
   * Encode a typed answer with [[ResumeRef.answer]].
   */
  def resume[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig(),
    durability: Durability = Durability.Sync,
    observer: Option[Observer] = None
  ): Result[RunHandle[O]] = exclusively(threadId, config, observer) { signal =>
    checkpointer.latest(threadId).flatMap {
      case None => Left(GraphError.NotSuspended(threadId.value))
      case Some(stored) =>
        checkTenant(threadId, stored, config).flatMap { _ =>
          if stored.checkpoint.status != CheckpointStatus.Suspended then Left(GraphError.NotSuspended(threadId.value))
          else
            for
              suspended <- graph.restore(stored.checkpoint.snapshot)
              resumed   <- graph.resume(suspended, answers)
              run <- newRun(graph, threadId, config, durability, resumed.superstep, signal).admit(
                resumed,
                Some(stored.checkpoint.id),
                RunEvent.RunResumed(
                  suspended.parked.map(_.interrupt).filter(answers.contains).map(_.value),
                  config.tenantId.map(_.value),
                  config.principal.map(_.value)
                )
              )
            yield run
        }
    }
  }

  /**
   * Whether run `runId` ended as a Block ([[NodeResult.Block]]): the thread's latest checkpoint is that
   * run's closing one, with status [[CheckpointStatus.Failed]]. An ordinary failure leaves no closing
   * checkpoint of its own, and a run started on the thread since makes this `false`.
   */
  private[agent] def endedBlocked(threadId: ThreadId, runId: RunId): Result[Boolean] =
    checkpointer
      .latest(threadId)
      .map(_.exists(s => s.checkpoint.runId == runId.value && s.checkpoint.status == CheckpointStatus.Failed))

  /**
   * Deletes `threadId` from the runtime's store - its checkpoint, pending writes, event log and claim -
   * so its id names a new thread again. Refused, with nothing deleted, as admission refuses a run: a
   * thread of another tenant is [[GraphError.TenantMismatch]], and one whose run is admitting or
   * executing in this runtime, or holds a live claim in any runtime, is [[GraphError.ThreadBusy]].
   * The thread is held exclusively, and claimed in the store under `config.runId`, while it is
   * deleted, so no run starts on it meanwhile. An unknown thread is `Right(())`. Cancel any
   * [[subscribe]] to the thread first: a new thread of the same id numbers its events from 1 again.
   */
  def deleteThread(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Unit] = admission(threadId) {
    val holder = withLock(activeLock) {
      val existing = active.get(threadId.value)
      if existing.isEmpty then active.update(threadId.value, (config.tenantId.map(_.value), config.runId))
      existing
    }
    holder match
      case Some((holderTenant, holderRun)) => busy(threadId, config, holderTenant, holderRun)
      case None                            =>
        // released on every exit, including an InterruptedException, which `Try` would not catch
        Using.resource(new AutoCloseable { def close(): Unit = release(threadId) }) { _ =>
          checkpointer
            .latest(threadId)
            .flatMap(_.fold[Result[Unit]](Right(()))(checkTenant(threadId, _, config)))
            .flatMap(_ => claimThread(threadId, config))
            .flatMap { claim =>
              // the deletion removes the claim with the thread; one that did not happen gives it back
              var deleted = false
              Using.resource(new AutoCloseable {
                def close(): Unit = if !deleted then releaseClaim(threadId, claim.token)
              }) { _ =>
                val outcome = checkpointer.deleteThread(threadId)
                deleted = outcome.isRight
                outcome
              }
            }
        }
  }

  /**
   * Asks the store for a claim on `threadId` for `config.runId`. A live claim held by another run is
   * [[GraphError.ThreadBusy]] naming it - after the thread is re-read and its tenant checked, so a
   * caller from another tenant gets `TenantMismatch` instead and learns nothing; for a thread with
   * no checkpoint, whose tenant cannot be checked, the holder is not named. Any other failure of the
   * store, returned or thrown (non-fatally), is [[GraphError.CheckpointWriteFailed]].
   */
  private def claimThread(threadId: ThreadId, config: RunConfig): Result[RunClaim] =
    Try(checkpointer.claim(threadId, ClaimRequest(config.runId, claims.ttl))).toResult.flatten match
      case Right(claim) => Right(claim)
      case Left(GraphError.ThreadBusy(_, latest, holder)) =>
        Try(busyAt(threadId, config, holder, nameWithoutCheckpoint = false)).toResult.flatten.left.map {
          case refusal @ (_: GraphError.TenantMismatch | _: GraphError.ThreadBusy) => refusal
          case _ => GraphError.ThreadBusy(threadId.value, latest)
        }
      case Left(other) => Left(GraphError.CheckpointWriteFailed(threadId.value, other))

  /**
   * Gives back the claim whose token is `token`, logging (never throwing) a store that fails: its
   * claim then lapses after `claims.ttl`, and until then the thread is `ThreadBusy` to every runtime.
   */
  private def releaseClaim(threadId: ThreadId, token: FencingToken): Unit =
    DefaultRunHandle.guarded(checkpointer.release(threadId, token)) match
      case Right(Right(_)) => ()
      case Right(Left(error)) =>
        GraphRuntime.logger.warn(
          s"Releasing the claim on thread '${threadId.value}' failed; it lapses after ${claims.ttl}: ${error.message}"
        )
      case Left(thrown) =>
        if thrown.isInstanceOf[InterruptedException] then Thread.currentThread().interrupt()
        GraphRuntime.logger.warn(
          s"Releasing the claim on thread '${threadId.value}' failed; it lapses after ${claims.ttl}",
          thrown
        )

  /**
   * Admits a run as the only run on `threadId` in this runtime, and launches it. While a run
   * executes, its thread's latest checkpoint is `Running`, which `recover` would otherwise take for
   * an abandoned run and run the same frontier again, repeating its side effects. The thread is
   * released here if admission fails or throws; once launched, the run thread releases it when it
   * exits, before the run's result is set. An `observer` joins the event hub before the claim
   * commits and is started once the run is admitted; a refused admission abandons it, delivering
   * nothing.
   */
  private def exclusively[I, O](threadId: ThreadId, config: RunConfig, observer: Option[Observer])(
    admit: StopSignal => Result[Run[I, O]]
  ): Result[RunHandle[O]] = admission(threadId) {
    observer.filter(_.capacity < 2) match
      case Some(o) =>
        Left(ValidationError("capacity", s"must be at least 2 (one slot is reserved for a LiveGap), was ${o.capacity}"))
      case None =>
        val reserving = (config.tenantId.map(_.value), config.runId)
        val holder = withLock(activeLock) {
          val existing = active.get(threadId.value)
          if existing.isEmpty then active.update(threadId.value, reserving)
          existing
        }
        holder match
          case Some((holderTenant, holderRun)) => busy(threadId, config, holderTenant, holderRun)
          case None =>
            val signal   = StopSignal()
            var launched = false
            var admitted = Option.empty[Run[I, O]]
            // joined before the claim commits, so it receives the claim and everything after it
            var observation = Option.empty[EventHub#Observation]
            // on every exit but a launch, including an InterruptedException, which `Try` would not
            // catch: the observer is abandoned, then the store claim and the thread released, so no
            // later run reaches it
            Using.resource(new AutoCloseable {
              def close(): Unit = if !launched then
                observation.foreach(_.abandon())
                admitted.foreach(_.releaseClaim())
                release(threadId)
            }) { _ =>
              observation = observer.map(o => hub.observe(threadId, o.capacity, o.listener))
              admit(signal).map { run =>
                admitted = Some(run)
                val subscription = observation.map(_.start(run.claimSeq - 1))
                val handle = DefaultRunHandle[O](
                  threadId,
                  run.runId,
                  run.claimSeq,
                  signal,
                  (afterSeq, capacity, listener) => dispatched(threadId, afterSeq, capacity, listener),
                  subscription
                )
                // the store claim first, then this runtime's hold: once a caller sees the result, any
                // runtime may claim the thread
                def releaseBoth(): Unit =
                  run.releaseClaim()
                  release(threadId)
                handle.launch(
                  () => run.execute(),
                  run.crashed,
                  () => releaseBoth(),
                  run.deadline,
                  Some(claims.renewEvery -> (() => run.renewClaim()))
                )
                launched = true
                handle
              }
            }
  }

  /**
   * Runs an admission, so that it never throws: a non-fatal throwable - from the store, the clock or
   * restoring the graph - is `Left(RunCrashed)`, and an `InterruptedException` is
   * `Left(CancelledError)` with the interrupt flag set again.
   */
  private def admission[A](threadId: ThreadId)(body: => Result[A]): Result[A] =
    CancelledError.catchInterrupt(Try(body)) match
      case Right(attempt) => attempt.fold(t => Left(GraphError.RunCrashed(threadId.value, t)), identity)
      case Left(interrupted) =>
        Thread.currentThread().interrupt()
        Left(CancelledError("graph run admission", Some(interrupted)))

  /**
   * Releases the thread's claim. The event hub forgets the thread's hand-over mark first, while the
   * thread is still held: every event of the run has been handed over, and no other run can claim
   * the thread, and set a new mark, until it is released.
   */
  private def release(threadId: ThreadId): Unit =
    hub.release(threadId)
    withLock(activeLock)(active.remove(threadId.value)): Unit

  /** Whether the event hub holds a hand-over mark for the thread; for tests. */
  private[graph] def handOverMarked(threadId: ThreadId): Boolean = hub.marked(threadId)

  private def started(config: RunConfig): RunEvent =
    RunEvent.RunStarted(config.tenantId.map(_.value), config.principal.map(_.value))

  /**
   * [[GraphError.ThreadBusy]] naming the thread's latest checkpoint and the run holding it in this
   * runtime - unless that run, or that checkpoint, belongs to another tenant: then
   * [[GraphError.TenantMismatch]], so that a caller from another tenant learns nothing about the
   * thread, not even that a run is live on it. The holder's tenant is checked first, because a first
   * admission on a new thread holds it before any checkpoint exists.
   */
  private def busy(
    threadId: ThreadId,
    config: RunConfig,
    holderTenant: Option[String],
    holderRun: RunId
  ): Result[Nothing] =
    val requested = config.tenantId.map(_.value)
    if holderTenant != requested then Left(GraphError.TenantMismatch(threadId.value, requested))
    else busyAt(threadId, config, Some(holderRun.value), nameWithoutCheckpoint = true)

  /**
   * [[busy]]'s store half: the thread's latest checkpoint, tenant-checked, and `holder`. With no
   * checkpoint the holder is named only if `nameWithoutCheckpoint`: its tenant is then known to be the
   * caller's, as for a run of this runtime.
   */
  private def busyAt(
    threadId: ThreadId,
    config: RunConfig,
    holder: Option[String],
    nameWithoutCheckpoint: Boolean
  ): Result[Nothing] =
    checkpointer.latest(threadId).flatMap {
      case None =>
        Left(GraphError.ThreadBusy(threadId.value, None, holder.filter(_ => nameWithoutCheckpoint)))
      case Some(stored) =>
        checkTenant(threadId, stored, config).flatMap(_ =>
          Left(GraphError.ThreadBusy(threadId.value, Some(stored.checkpoint.id), holder))
        )
    }

  /** A thread belongs to the tenant on its latest checkpoint; `None` and `Some` differ. */
  private def checkTenant(threadId: ThreadId, stored: StoredCheckpoint, config: RunConfig): Result[Unit] =
    val requested = config.tenantId.map(_.value)
    Either.cond(
      stored.checkpoint.tenantId == requested,
      (),
      GraphError.TenantMismatch(threadId.value, requested)
    )

  private def pendingInterrupts(threadId: ThreadId, stored: StoredCheckpoint): GraphError =
    GraphError.PendingInterrupts(threadId.value, stored.checkpoint.snapshot.parked.map(_.interruptId).toList)

  private def newRun[I, O](
    graph: CompiledGraph[I, O],
    threadId: ThreadId,
    config: RunConfig,
    durability: Durability,
    firstSuperstep: Int,
    signal: StopSignal
  ): Run[I, O] =
    new Run(graph, threadId, config, committer(threadId, durability), firstSuperstep, signal)

  private def reusableWrites(
    graph: CompiledGraph[?, ?],
    execution: Execution,
    stored: StoredCheckpoint
  ): Result[Map[TaskId, TaskResult]] =
    val frontier = execution.frontier.map(_.id).toSet
    stored.pendingWrites.foldLeft[Result[Map[TaskId, TaskResult]]](Right(Map.empty)) { (acc, write) =>
      acc.flatMap { reused =>
        if !frontier.contains(TaskId(write.taskId)) then
          Left(
            GraphError.RestoreRejected(graph.id, List(s"pending write for task ${write.taskId}, which is not pending"))
          )
        else
          val task = execution.frontier.find(_.id == TaskId(write.taskId)).get
          for
            _ <- Either.cond(
              write.nodeId == task.node.value,
              (),
              GraphError.RestoreRejected(
                graph.id,
                List(s"pending write for task ${write.taskId} names node '${write.nodeId}', not '${task.node.value}'")
              )
            )
            result <- graph.decodeWrite(write)
            _      <- graph.checkResult(task, result)
          yield reused.updated(task.id, result)
      }
    }

  private def committer(threadId: ThreadId, durability: Durability): Committer =
    durability match
      case Durability.Sync   => SyncCommitter(threadId)
      case Durability.Async  => AsyncCommitter(threadId)
      case Durability.OnExit => OnExitCommitter(threadId)

  /**
   * Commits and delivers under one lock, so delivery order is commit order; returns the committed
   * events. A store that throws (non-fatally) rather than returning `Left` fails the commit the
   * same way; an `InterruptedException` is not a store failure and propagates.
   */
  private def commitAndDeliver(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
    withLock(commitLock) {
      Try(checkpointer.commit(threadId, commit)).toResult.flatten.map { records =>
        hub.durable(threadId, records)
        records
      }
    }

  private trait Committer:
    def submit(commit: Commit): Unit
    def failure: Option[LLMError]

    /**
     * Forgets a failed commit, so that the next is tried. Called only when the run is stopped,
     * because the stop's interrupt can fail a synchronous commit on the run thread; a commit that
     * fails again is reported as before. Async and OnExit commits are never made on an
     * interruptible run thread, so they keep their failure.
     */
    def forgetInterruptedFailure(): Unit = ()

    /** Makes everything submitted durable (or fails), and releases resources. */
    def close(): Unit

  final private class SyncCommitter(threadId: ThreadId) extends Committer:
    private val failed = AtomicReference[Option[LLMError]](None)
    def submit(commit: Commit): Unit =
      if failed.get.isEmpty then
        commitAndDeliver(threadId, commit).left.foreach(e => failed.compareAndSet(None, Some(e)))
    def failure: Option[LLMError]                 = failed.get
    def close(): Unit                             = ()
    override def forgetInterruptedFailure(): Unit = failed.set(None)

  final private class AsyncCommitter(threadId: ThreadId) extends Committer:
    private val failed = AtomicReference[Option[LLMError]](None)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable =>
      val thread = new Thread(runnable, s"llm4s-graph-writer-${threadId.value}")
      thread.setDaemon(true)
      thread
    }
    def submit(commit: Commit): Unit =
      writer.execute { () =>
        // a failed commit stops the queue: a later commit must not land on top of a lost one
        if failed.get.isEmpty then
          commitAndDeliver(threadId, commit).left.foreach(e => failed.compareAndSet(None, Some(e)))
      }
    def failure: Option[LLMError] = failed.get

    /**
     * Waits for the queue to drain, even if interrupted: returning earlier would release the thread
     * claim while queued commits are still landing. Never throws; an interrupt is not a commit
     * failure, so it is only remembered and the flag set again once the queue has drained.
     */
    def close(): Unit =
      writer.shutdown()
      @tailrec def drain(interrupted: Boolean): Boolean =
        CancelledError.catchInterrupt(writer.awaitTermination(Long.MaxValue, TimeUnit.NANOSECONDS)) match
          case Right(_) => interrupted
          case Left(_)  => drain(interrupted = true)
      if drain(interrupted = false) then Thread.currentThread().interrupt()

  final private class OnExitCommitter(threadId: ThreadId) extends Committer:
    private var durableParent: Option[Option[String]] = None
    private var checkpoint: Option[Checkpoint]        = None
    private var writes: Vector[PendingWrite]          = Vector.empty
    private var events: Vector[EventDraft]            = Vector.empty
    private var failed: Option[LLMError]              = None
    private var token: Option[FencingToken]           = None
    private val lock                                  = new java.util.concurrent.locks.ReentrantLock()

    def submit(commit: Commit): Unit = withLock(lock) {
      // every commit of a run carries its claim's token; the exit commit carries it too
      if token.isEmpty then token = Some(commit.token)
      commit.checkpoint.foreach { next =>
        if durableParent.isEmpty then durableParent = Some(next.parent)
        checkpoint = Some(next)
        writes = Vector.empty
      }
      writes ++= commit.pendingWrites
      events ++= commit.events
    }
    def failure: Option[LLMError] = withLock(lock)(failed)
    def close(): Unit = withLock(lock) {
      val last = checkpoint.map(_.withParent(durableParent.flatten))
      token.foreach { fence =>
        if last.isDefined || writes.nonEmpty || events.nonEmpty then
          failed = commitAndDeliver(threadId, Commit(fence, last, writes, events)).left.toOption
      }
    }

  /** One run: drives supersteps, recording task results, checkpoints and events as it goes. */
  final private class Run[I, O](
    graph: CompiledGraph[I, O],
    threadId: ThreadId,
    config: RunConfig,
    committer: Committer,
    firstSuperstep: Int,
    signal: StopSignal
  ):
    val runId               = config.runId
    private val cause       = signal.cause
    private var checkpoints = 0

    /** Where the run is: the execution `loop` is at and its checkpoint; set by the claim. */
    private var position: (Execution, String) = null

    /** What the claim leaves for `execute`: pending writes `loop` reuses. */
    private var claimedReused: Map[TaskId, TaskResult] = Map.empty

    /** The sequence number of the claim's event; set by the claim. */
    var claimSeq: Long = 0L

    /** When `budgets.timeout` expires, as a `System.nanoTime` value; fixed by the claim. */
    var deadline: Option[Long] = None

    /**
     * Claims the thread in two steps: a [[RunClaim]] from the store, then a synchronous commit of
     * `execution` as a new checkpoint whose parent is `parent`, carrying `carried` pending writes over
     * to it, fenced by the claim's token; [[execute]] then runs from there. A live claim of another
     * run, or a conflicting commit - another run advanced the thread first - is `ThreadBusy`. If the
     * commit does not land, or anything throws, the store claim is given back before this returns.
     */
    def admit(
      execution: Execution,
      parent: Option[String],
      event: RunEvent,
      carried: Vector[PendingWrite] = Vector.empty,
      reused: Map[TaskId, TaskResult] = Map.empty
    ): Result[Run[I, O]] =
      claimThread(threadId, config).flatMap { granted =>
        claim = Some(granted)
        var admitted = false
        Using.resource(new AutoCloseable { def close(): Unit = if !admitted then releaseClaim() }) { _ =>
          val outcome = committed(execution, parent, event, carried, reused)
          admitted = outcome.isRight
          outcome
        }
      }

    private def committed(
      execution: Execution,
      parent: Option[String],
      event: RunEvent,
      carried: Vector[PendingWrite],
      reused: Map[TaskId, TaskResult]
    ): Result[Run[I, O]] =
      for
        claimed <- newCheckpoint(execution, parent, CheckpointStatus.Running)
        records <- commitAndDeliver(
          threadId,
          Commit(
            token,
            Some(claimed),
            carried.map(_.copy(checkpointId = claimed.id)),
            Vector(draft(Some(claimed.id), None, event))
          )
        ) match
          // another run advanced the thread first: the thread is re-read so that another tenant's
          // checkpoint is not named, but the conflict's own `latest` is the authoritative one. A
          // re-read that fails or throws falls back to that ThreadBusy rather than a store error.
          case Left(GraphError.CheckpointConflict(_, _, latest)) =>
            Try(busyAt(threadId, config, None, nameWithoutCheckpoint = false)).toResult.flatten.left.map {
              case mismatch: GraphError.TenantMismatch => mismatch
              case _                                   => GraphError.ThreadBusy(threadId.value, latest)
            }
          case Left(other)    => Left(GraphError.CheckpointWriteFailed(threadId.value, other))
          case Right(records) => Right(records)
        seq <- records.headOption
          .map(_.seq)
          .toRight(
            GraphError.CheckpointWriteFailed(
              threadId.value,
              GraphError.InvalidCommit(
                threadId.value,
                "the store returned no event for the claim's RunStarted, RunRecovered or RunResumed"
              )
            )
          )
      yield
        position = execution -> claimed.id
        claimedReused = reused
        claimSeq = seq
        deadline = config.budgets.timeout.map(timeout => System.nanoTime() + timeout.toNanos)
        this

    /** The store claim this run holds, from [[admit]] on; `None` before it, or if it was refused. */
    @volatile private var claim: Option[RunClaim] = None
    private val claimReleased                     = new java.util.concurrent.atomic.AtomicBoolean(false)

    /** The token every commit of this run carries; set by [[admit]] before its first commit. */
    private def token: FencingToken = claim.map(_.token).getOrElse(FencingToken(0L))

    /** Gives back the store claim, once; a no-op before the claim was granted. Never throws. */
    def releaseClaim(): Unit =
      claim.foreach(c =>
        if claimReleased.compareAndSet(false, true) then GraphRuntime.this.releaseClaim(threadId, c.token)
      )

    /**
     * Renews the store claim for another `claims.ttl`; on the claim's renewal thread. Returns whether
     * to keep renewing: `false` once the claim has been released or lost ([[GraphError.StaleClaim]]:
     * another run took the thread over, and this run's commits are now refused). A store that fails
     * otherwise is logged, and renewal is tried again at the next interval. Never throws.
     */
    def renewClaim(): Boolean =
      claim match
        case None                         => false
        case Some(_) if claimReleased.get => false
        case Some(held) =>
          DefaultRunHandle.guarded(checkpointer.renew(threadId, held.token, claims.ttl)) match
            case Right(Right(_)) => true
            case Right(Left(lost: GraphError.StaleClaim)) =>
              if !claimReleased.get then
                GraphRuntime.logger.warn(
                  s"Run '${runId.value}' lost its claim on thread '${threadId.value}': ${lost.message}; its commits will be refused"
                )
              false
            case Right(Left(error)) =>
              GraphRuntime.logger.warn(
                s"Renewing run '${runId.value}''s claim on thread '${threadId.value}' failed: ${error.message}"
              )
              true
            case Left(thrown) =>
              GraphRuntime.logger.warn(
                s"Renewing run '${runId.value}''s claim on thread '${threadId.value}' failed",
                thrown
              )
              true

    /** Runs the claimed execution to its end; on the run thread. */
    def execute(): RunResult[O] =
      val (execution, checkpointId) = position
      // an interrupt thrown from anywhere in the loop, such as joining a superstep's tasks
      CancelledError.catchInterrupt(loop(execution, checkpointId, claimedReused)).fold(_ => cancelled(), identity)

    /**
     * Ends a run that `thrown` escaped from: the committer is closed as far as it can be, and the
     * run fails where it was, recording nothing more.
     */
    def crashed(thrown: Throwable): RunResult[O] =
      DefaultRunHandle.guarded(committer.close()).left.foreach(thrown.addSuppressed)
      RunResult.Failed(position._1.state, GraphError.RunCrashed(threadId.value, thrown))

    /**
     * Drives supersteps. `position` advances only once the previous turn's commits are known to
     * have succeeded, so a run stopped after a failed commit names the last durable checkpoint.
     */
    @tailrec private def loop(
      execution: Execution,
      checkpointId: String,
      reused: Map[TaskId, TaskResult]
    ): RunResult[O] =
      committer.failure match
        // a commit the stop's interrupt broke: the run was stopped, not failed by its store
        case Some(_) if stopRecorded => cancelled()
        case Some(error)             => stop(execution, GraphError.CheckpointWriteFailed(threadId.value, error))
        case None =>
          position = execution -> checkpointId
          if execution.paused || execution.isQuiescent then end(execution, checkpointId)
          else if execution.superstep - firstSuperstep >= config.budgets.maxSupersteps then
            fail(execution, GraphError.SuperstepLimitExceeded(config.budgets.maxSupersteps))
          else if Thread.currentThread().isInterrupted || overdue() then cancelled()
          else
            val outcomes = graph
              .executorFor(config.budgets)
              .runAll(execution.frontier.map { task => () =>
                reused.get(task.id).fold(runTask(task, execution, checkpointId))(Right(_)).map(task -> _)
              })
            // a cancelled task cancels the run, even if an earlier task failed
            if outcomes.exists { case Left(_: CancelledError) => true; case _ => false } then cancelled()
            else
              val completed =
                outcomes.foldLeft[Result[Vector[(Task, TaskResult)]]](Right(Vector.empty))((done, outcome) =>
                  done.flatMap(cs => outcome.map(cs :+ _))
                )
              // the first blocked task, in frontier order, ends the run once the superstep is committed
              val blockedBy = completed.toOption.flatMap(graph.blockedBy)
              completed.flatMap(graph.commitSuperstep(execution, _)) match
                case Left(error) => fail(execution, error)
                case Right(next) =>
                  blockedBy match
                    case Some(error)         => block(next, checkpointId, error)
                    case None if next.paused => end(next, checkpointId)
                    case None =>
                      checkpoint(
                        next,
                        Some(checkpointId),
                        CheckpointStatus.Running,
                        RunEvent.CheckpointCommitted(next.superstep)
                      ) match
                        case Left(error) => fail(execution, error)
                        case Right(id)   => loop(next, id, Map.empty)

    /** Whether a cancel or expiry has been recorded, and so may have interrupted a commit. */
    private def stopRecorded: Boolean =
      cause.get.exists(c => c == StopCause.Cancelled || c == StopCause.Expired)

    /**
     * Whether the deadline has passed, recording `Expired` unless a cause is already recorded. The
     * deadline thread interrupts the run when it expires; this catches a deadline that passed before
     * the loop started, or between that interrupt's check and the next superstep.
     */
    private def overdue(): Boolean =
      val due = deadline.exists(d => System.nanoTime() - d >= 0)
      if due then cause.compareAndSet(None, Some(StopCause.Expired)): Unit
      due

    /**
     * Ends a run that cannot advance: completed, suspended (persisted before returning) or failed.
     * Before submitting the completed or suspended checkpoint it records [[StopCause.Finishing]], so
     * no cancel or expiry can interrupt that commit; if a cancel or expiry was recorded first, the
     * run is stopped instead and its outcome is not committed.
     */
    private def end(execution: Execution, checkpointId: String): RunResult[O] =
      graph.finish(execution) match
        case RunResult.Failed(_, error) => fail(execution, error)
        case result =>
          val (status, event) = result match
            case suspended: RunResult.Suspended =>
              CheckpointStatus.Suspended -> RunEvent.RunSuspended(suspended.interrupts.map(_.id.value))
            case _ => CheckpointStatus.Completed -> RunEvent.RunCompleted
          newCheckpoint(execution, Some(checkpointId), status) match
            case Left(error)                                                       => fail(execution, error)
            case Right(_) if !cause.compareAndSet(None, Some(StopCause.Finishing)) => cancelled()
            case Right(saved) =>
              submit(saved, event)
              committer.close()
              committer.failure.fold(result)(e =>
                RunResult.Failed(execution.state, GraphError.CheckpointWriteFailed(threadId.value, e))
              )

    /**
     * Ends a run a task blocked: the superstep's updates are committed, so `execution` is the state the
     * thread keeps, and the closing checkpoint is [[CheckpointStatus.Failed]] with a
     * [[RunEvent.RunFailed]]. Like [[end]], it records [[StopCause.Finishing]] before submitting, so no
     * later cancel or expiry interrupts that commit; if one was recorded first, the run is stopped instead.
     * The caller gets `error` itself.
     */
    private def block(execution: Execution, parent: String, error: LLMError): RunResult[O] =
      newCheckpoint(execution, Some(parent), CheckpointStatus.Failed) match
        case Left(storeError)                                                  => fail(execution, storeError)
        case Right(_) if !cause.compareAndSet(None, Some(StopCause.Finishing)) => cancelled()
        case Right(saved) =>
          submit(saved, RunEvent.RunFailed(error.message))
          committer.close()
          committer.failure.fold[RunResult[O]](RunResult.Failed(execution.state, error))(e =>
            RunResult.Failed(execution.state, GraphError.CheckpointWriteFailed(threadId.value, e, Some(error)))
          )

    private def runTask(task: Task, execution: Execution, checkpointId: String): Result[TaskResult] =
      val sink = TaskSink(task, checkpointId)
      val context = new RunContext(
        config,
        RunPosition(threadId, runId, checkpointId, task.id, task.node, execution.superstep, claim.map(_.token)),
        sink
      )
      def settled(executed: Result[TaskResult]): Result[TaskResult] =
        executed.flatMap(result => graph.encodeWrite(checkpointId, task, result).map(result -> _)) match
          // no write and no event: `recover` runs the task again
          case Left(cancelled: CancelledError)                  => Left(cancelled)
          case Right(_) if Thread.currentThread().isInterrupted => Left(CancelledError(s"task ${task.id.value}"))
          case Right((result, write)) =>
            val event = result match
              case TaskResult.Done(_)         => RunEvent.TaskCompleted
              case TaskResult.Parked(_, _, _) => RunEvent.TaskSuspended(task.id.value)
              case TaskResult.Blocked(_, e)   => RunEvent.TaskFailed(e.message)
            committer.submit(
              Commit(token, None, Vector(write), draft(Some(checkpointId), Some(task), event) +: sink.customEvents)
            )
            Right(result)
          case Left(error) =>
            committer.submit(
              Commit(
                token,
                None,
                Vector.empty,
                Vector(draft(Some(checkpointId), Some(task), RunEvent.TaskFailed(error.message)))
              )
            )
            Left(error)
      graph.executeTask(task, execution, context) match
        case Right(_: TaskResult.Blocked) if Thread.currentThread().isInterrupted =>
          Left(CancelledError(s"task ${task.id.value}"))
        // a blocked task has no pending write, since its error is not data: its event records the failure,
        // and the run ends with the superstep
        case Right(blocked @ TaskResult.Blocked(_, error)) =>
          committer.submit(
            Commit(
              token,
              None,
              Vector.empty,
              draft(Some(checkpointId), Some(task), RunEvent.TaskFailed(error.message)) +: sink.customEvents
            )
          )
          Right(blocked)
        case executed => settled(executed)

    private def newCheckpoint(
      execution: Execution,
      parent: Option[String],
      status: CheckpointStatus
    ): Result[Checkpoint] =
      graph.snapshot(execution).map { snapshot =>
        checkpoints += 1
        val id = s"${runId.value}/$checkpoints"
        Checkpoint(
          Checkpoint.CurrentFormat,
          id,
          parent,
          threadId.value,
          runId.value,
          status,
          clock.instant(),
          snapshot,
          config.tenantId.map(_.value),
          claim.map(_.token)
        )
      }

    private def checkpoint(
      execution: Execution,
      parent: Option[String],
      status: CheckpointStatus,
      event: RunEvent
    ): Result[String] =
      newCheckpoint(execution, parent, status).map { saved =>
        submit(saved, event)
        saved.id
      }

    private def submit(saved: Checkpoint, event: RunEvent): Unit =
      committer.submit(Commit(token, Some(saved), Vector.empty, Vector(draft(Some(saved.id), None, event))))

    /**
     * Ends a cancelled or expired run, by its recorded cause: [[RunEvent.RunCancelled]] and
     * [[GraphError.Cancelled]], or [[RunEvent.RunTimedOut]] and [[GraphError.DeadlineExceeded]]. The
     * interrupt is cleared so the closing commits can complete, then set again before returning, so
     * the caller still sees it. A synchronous commit that the interrupt failed is forgotten, so the
     * closing commits are tried; if they fail too, the run reports `CheckpointWriteFailed`. The
     * checkpoint stays `Running`, and the error names it - the last durable checkpoint, which
     * `recover` continues from, also under OnExit, whose exit commit keeps its id.
     */
    private def cancelled(): RunResult[O] =
      val (execution, checkpointId) = position
      // acknowledged before the flag is cleared: a stop that recorded its cause but has not yet
      // interrupted now never will, so no interrupt can land on the closing commits below
      signal.acknowledge()
      Thread.interrupted(): Unit
      // an interrupt with no recorded cause came from inside the run, such as a node; it cancels too.
      // Recording it makes a later cancel or expiry fail its compare-and-set and send no interrupt,
      // which would otherwise land on the closing commits below.
      cause.compareAndSet(None, Some(StopCause.Cancelled)): Unit
      committer.forgetInterruptedFailure()
      val (event, error) = cause.get.getOrElse(StopCause.Cancelled) match
        // `Finishing` here means an interrupt from inside the run reached the outcome's commit
        case StopCause.Cancelled | StopCause.Finishing =>
          RunEvent.RunCancelled -> GraphError.Cancelled(Some(threadId.value), Some(checkpointId))
        case StopCause.Expired =>
          RunEvent.RunTimedOut -> GraphError.DeadlineExceeded(threadId.value, Some(checkpointId))
      committer.submit(Commit(token, None, Vector.empty, Vector(draft(Some(checkpointId), None, event))))
      val result = stop(execution, error)
      Thread.currentThread().interrupt()
      result

    /**
     * Fails the run with `error`, committing [[RunEvent.RunFailed]]. Like [[end]], it records
     * [[StopCause.Finishing]] first, so a later cancel or expiry sends no interrupt into that commit
     * (or OnExit's exit commit); if a cancel or expiry was recorded first, the run is stopped
     * instead.
     */
    private def fail(execution: Execution, error: LLMError): RunResult[O] =
      if !cause.compareAndSet(None, Some(StopCause.Finishing)) then cancelled()
      else
        committer.submit(
          Commit(token, None, Vector.empty, Vector(draft(None, None, RunEvent.RunFailed(error.message))))
        )
        stop(execution, error)

    /**
     * Closes the committer and fails the run. If closing reveals that a commit failed - an Async
     * queue, or OnExit's one exit commit - the run was not made durable, and the result says so
     * ([[GraphError.CheckpointWriteFailed]], keeping `error` as the run's own failure).
     */
    private def stop(execution: Execution, error: LLMError): RunResult[O] =
      committer.close()
      val reported = (error, committer.failure) match
        case (already: GraphError.CheckpointWriteFailed, _) => already
        case (_, Some(storeError)) => GraphError.CheckpointWriteFailed(threadId.value, storeError, Some(error))
        case (_, None)             => error
      RunResult.Failed(execution.state, reported)

    private def draft(checkpointId: Option[String], task: Option[Task], event: RunEvent): EventDraft =
      EventDraft(runId.value, checkpointId, task.map(_.id.value), task.map(_.node.value), clock.instant(), event)

    /** Buffers a task's custom events for its commit; forwards its progress live. */
    final private class TaskSink(task: Task, checkpointId: String) extends NodeEventSink:
      private val buffered                 = mutable.ArrayBuffer.empty[EventDraft]
      private val lock                     = new java.util.concurrent.locks.ReentrantLock()
      def customEvents: Vector[EventDraft] = withLock(lock)(buffered.toVector)
      // payloads are copied at the call: ujson values are mutable, and a node may reuse one
      def custom(name: String, version: Int, payload: ujson.Value): Unit =
        val snapshot = ujson.copy(payload)
        withLock(lock) {
          buffered += draft(Some(checkpointId), Some(task), RunEvent.Custom(name, version, snapshot))
        }
      def discardCustom(): Unit = withLock(lock)(buffered.clear())
      def progress(name: String, version: Int, payload: ujson.Value): Unit =
        hub.live(
          threadId,
          StreamEvent.Live(
            threadId.value,
            runId.value,
            task.id.value,
            task.node.value,
            name,
            version,
            ujson.copy(payload)
          )
        )
