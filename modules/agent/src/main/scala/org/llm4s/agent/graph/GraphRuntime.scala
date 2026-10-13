package org.llm4s.agent.graph

import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory

import java.time.Clock
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ CountDownLatch, ExecutorService, Executors, TimeUnit }
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

  /**
   * The id of a new checkpoint that run `runId` writes over `parent`: `<runId>/<n>`, where `n` is one
   * more than the parent's - so `n` counts along the thread's history, and an id is never repeated on
   * a thread even when one `RunId` serves several runs. A parent id of another form counts as 0.
   */
  private[graph] def checkpointId(runId: RunId, parentOrdinal: Long, written: Long): String =
    s"${runId.value}/${parentOrdinal + written}"

  /** The `n` of a `<runId>/<n>` checkpoint id; 0 for an id of another form, or none. */
  private[graph] def ordinal(checkpointId: Option[String]): Long =
    checkpointId
      .map(id => id.substring(id.lastIndexOf('/') + 1))
      .flatMap(_.toLongOption)
      .filter(_ >= 0)
      .getOrElse(0L)

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
 * Each call's [[RunConfig]] brings the run's static breakpoints; one on a node the graph does not have
 * is refused with a `ValidationError` before the thread is read. A task they hold parks like a task
 * that suspended, so the thread ends `Suspended` and `resume` answers it with [[Breakpoint.proceed]].
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
 * cannot record anything after its successor started. The run also judges its own lease, by its
 * monotonic clock, so it learns of the loss even when no renewal reaches the store: once the lease
 * passes with no renewal landing, or a renewal or commit is refused as stale, it stops at once - its
 * tasks interrupted, as a cancel interrupts them - commits nothing more, and fails with
 * [[GraphError.ClaimLost]]. A `recover` that takes over an expired claim records the run it took the
 * thread from ([[RunEvent.RunRecovered]]). A call on a thread whose run is still executing in this
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
 *
 * A subscription also sees the durable events of commits made through another runtime or process
 * sharing the checkpointer, in the same order and with the same guarantees: each live subscription
 * watches the store ([[WatchPolicy]], [[Checkpointer.awaitEventsAfter]]), so they arrive as soon as
 * the store reports them - at once from a store that is told of commits, within `pollInterval` from
 * one that is polled. Live progress events are never stored, so another runtime's do not arrive.
 */
final class GraphRuntime(
  checkpointer: Checkpointer,
  clock: Clock = Clock.systemUTC(),
  claims: ClaimPolicy = ClaimPolicy.default,
  watch: WatchPolicy = WatchPolicy.default
):

  /** For callers that cannot use default arguments (Java, Kotlin): [[WatchPolicy.default]]. */
  def this(checkpointer: Checkpointer, clock: Clock, claims: ClaimPolicy) =
    this(checkpointer, clock, claims, WatchPolicy.default)

  /** For callers that cannot use default arguments (Java, Kotlin): [[ClaimPolicy.default]] and [[WatchPolicy.default]]. */
  def this(checkpointer: Checkpointer, clock: Clock) =
    this(checkpointer, clock, ClaimPolicy.default, WatchPolicy.default)

  /**
   * For callers that cannot use default arguments (Java, Kotlin): the system clock, [[ClaimPolicy.default]]
   * and [[WatchPolicy.default]].
   */
  def this(checkpointer: Checkpointer) = this(checkpointer, Clock.systemUTC(), ClaimPolicy.default, WatchPolicy.default)

  /** Every commit is written and handed to the event hub under it; the hub's store watches take it too. */
  private val commitLock = new java.util.concurrent.locks.ReentrantLock()
  private val hub        = EventHub(checkpointer, commitLock, watch)

  /**
   * Threads with a run admitting or executing in this runtime, each with that run's tenant, so a
   * caller from another tenant is refused even before the thread's first checkpoint exists, and its
   * run id, the holder `ThreadBusy` names; guarded by `activeLock`.
   */
  private val active     = mutable.Map.empty[String, (Option[String], RunId)]
  private val activeLock = new java.util.concurrent.locks.ReentrantLock()

  /**
   * The monotonic clock, in nanoseconds, by which a run judges its own lease: `System.nanoTime`. A
   * test replaces it to move a lease's end without waiting for it.
   */
  @volatile private[graph] var ticker: () => Long = () => System.nanoTime()

  /**
   * A test hook run by an Async committer that a lost claim abandoned, once it refuses every commit
   * not yet written and before it waits for one in flight; a no-op by default.
   */
  @volatile private[graph] var abandoning: ThreadId => Unit = _ => ()

  /** A test hook run by a claim's renewal thread once it has acted on the store's answer; a no-op by default. */
  @volatile private[graph] var renewalSettled: ThreadId => Unit = _ => ()

  /**
   * Replays events with `seq > afterSeq`, then delivers new events until cancelled or
   * disconnected. The subscription is scoped to the thread, so it delivers every later run on it,
   * and its dispatcher (a virtual thread, parked while idle) lives until [[Subscription.cancel]];
   * see there for how cancel waits on a running listener. Commits made through this runtime are
   * handed over as they land; those of another runtime or process sharing the checkpointer are read
   * from the store by the subscription's watch ([[WatchPolicy]]: a second virtual thread, which
   * `cancel` stops too), in the same order and with the same guarantees, and are queued only as they
   * fit, so they never make it lag. Live progress of another runtime's runs is not delivered: it is
   * never stored. With [[WatchPolicy.disabled]], another runtime's commits are seen only by
   * subscribing again, which replays the log. A failed read of the store ends the subscription,
   * after what is queued, with [[DisconnectReason.ReplayFailed]]. Returns without
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

  /** How many subscriptions to `threadId` are watching the store; for tests. */
  private[llm4s] def storeWatches(threadId: ThreadId): Int = hub.watchCount(threadId)

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
    graph.checkBreakpoints(config).flatMap(_ => checkpointer.latest(threadId)).flatMap {
      case None =>
        newRun(graph, threadId, config, durability, 0, signal).admit(graph.start(input), None, _ => started(config))
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
                        _ => started(config)
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
    graph.checkBreakpoints(config).flatMap(_ => checkpointer.latest(threadId)).flatMap {
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
                  // a claim that replaced another run's expired one is a takeover: that run may still be executing
                  granted =>
                    RunEvent.RunRecovered(
                      stored.checkpoint.id,
                      config.tenantId.map(_.value),
                      config.principal.map(_.value),
                      granted.takenOverFrom.filter(_ != config.runId).map(_.value)
                    ),
                  // read again under the claim: a holder whose claim expired but was not yet replaced
                  // could still add pending writes after the read above, and only the claim stops it
                  () =>
                    checkpointer.latest(threadId).flatMap {
                      case Some(now) if now.checkpoint.id == stored.checkpoint.id =>
                        reusableWrites(graph, execution, now).map(now.pendingWrites -> _)
                      // the thread moved on: the claim commit's parent no longer matches, so it conflicts
                      case _ => Right(stored.pendingWrites -> reused)
                    }
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
    graph.checkBreakpoints(config).flatMap(_ => checkpointer.latest(threadId)).flatMap {
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
                _ =>
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
   * The tenant is checked once more after the store claim is granted, so a thread another runtime
   * created for another tenant in between is `TenantMismatch`, not deleted.
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
                // checked again under the claim: another runtime may have created the thread, for
                // another tenant, between the read above and the claim, and nothing else stops it
                // before the delete (the delete, unlike a commit, names no parent checkpoint)
                checkpointer
                  .latest(threadId)
                  .flatMap(_.fold[Result[Unit]](Right(()))(checkTenant(threadId, _, config)))
                  .flatMap { _ =>
                    val outcome = checkpointer.deleteThread(threadId)
                    deleted = outcome.isRight
                    outcome
                  }
              }
            }
        }
  }

  /**
   * Up to `limit` of `threadId`'s checkpoints, newest first - the latest is the first of the first page
   * - starting after checkpoint `before` when it is given: pass the last id of a page to read the
   * next. Each is data; read its typed state with `graph.restore(checkpoint.snapshot)`. The history
   * holds every checkpoint the thread's runs, [[updateState]] and [[fork]] committed, except those
   * [[prune]] removed and the earlier checkpoints of a turn a guardrail blocked ([[NodeResult.Block]]
   * retracts its turn). The retraction leaves the closing `Failed` checkpoint, which holds whatever the
   * blocking node's own `Block` update leaves in the state: `Agent` removes the turn there with
   * `MessageUpdate.RemoveTurn`, and a custom blocking node must remove the content in its `Block` update
   * likewise. A thread of another tenant is [[GraphError.TenantMismatch]]; a `before` not in the history
   * is [[GraphError.CheckpointNotFound]]; a new thread has an empty history. `limit` must be positive
   * (`ValidationError`). Reading needs no claim, so it never waits for a run.
   */
  def history(
    threadId: ThreadId,
    before: Option[String] = None,
    limit: Int = 100,
    config: RunConfig = RunConfig()
  ): Result[Vector[Checkpoint]] = admission(threadId) {
    if limit < 1 then Left(ValidationError("limit", s"must be at least 1, was $limit"))
    else
      checkpointer.latest(threadId).flatMap {
        case None => before.fold[Result[Vector[Checkpoint]]](Right(Vector.empty))(notFound(threadId, _))
        case Some(stored) =>
          for
            _    <- checkTenant(threadId, stored, config)
            page <- checkpointer.history(threadId, before, limit)
            _    <- page.map(owned(threadId, _, config)).find(_.isLeft).getOrElse(Right(()))
          yield page
      }
  }

  /**
   * Checkpoint `checkpointId` of `threadId`'s history (see [[history]]), the latest included.
   * [[GraphError.CheckpointNotFound]] if it is not there; [[GraphError.TenantMismatch]] for a thread of
   * another tenant.
   */
  def checkpoint(threadId: ThreadId, checkpointId: String, config: RunConfig = RunConfig()): Result[Checkpoint] =
    admission(threadId) {
      checkpointer.latest(threadId).flatMap {
        case None => notFound(threadId, checkpointId)
        case Some(stored) =>
          checkTenant(threadId, stored, config)
            .flatMap(_ => checkpointer.checkpoint(threadId, checkpointId))
            .flatMap(
              _.fold[Result[Checkpoint]](notFound(threadId, checkpointId))(c => owned(threadId, c, config).map(_ => c))
            )
      }
    }

  /**
   * Starts thread `target` from checkpoint `checkpointId` of thread `source`, and returns the new
   * thread's first checkpoint: the source checkpoint's state, scheduled work, parked interrupts and
   * status, with no parent, written by `config.runId` and recorded with a [[RunEvent.ThreadForked]]
   * event, the first of the new thread's log. Continue it as its status says: `start` after `Completed`
   * or `Failed`, `recover` after `Running` - which runs the checkpoint's pending tasks again, in the new
   * thread, because pending writes are never copied - and `resume` after `Suspended`. The source thread
   * is only read, so it may be running meanwhile, and is left unchanged.
   *
   * Refused, with nothing changed: a source of another tenant is [[GraphError.TenantMismatch]], a
   * checkpoint not in its history [[GraphError.CheckpointNotFound]]; a target that is the source is a
   * `ValidationError`, one that exists [[GraphError.ThreadExists]] (or `TenantMismatch` if it is
   * another tenant's), and one held by a run [[GraphError.ThreadBusy]]. The target is claimed in the
   * store while it is created.
   *
   * The snapshot is copied as it is, so tool calls already issued at the forked checkpoint keep the
   * source thread's idempotency keys, which the snapshot records (once #1739 lands, in their `ToolTask`,
   * `ApprovalRequest` or `ToolQuestionRequest`); only model calls the new thread makes afterwards derive
   * new keys, since the key includes the thread. This is intended: a fork that re-runs a call the source
   * already issued is de-duplicated against the source's call by its key, rather than performing the
   * side effect a second time.
   */
  def fork(
    source: ThreadId,
    checkpointId: String,
    target: ThreadId,
    config: RunConfig = RunConfig()
  ): Result[Checkpoint] = admission(target) {
    val tenant = config.tenantId.map(_.value)
    def origin: Result[Checkpoint] =
      checkpointer.latest(source).flatMap {
        case None => notFound(source, checkpointId)
        case Some(stored) =>
          checkTenant(source, stored, config)
            .flatMap(_ => checkpointer.checkpoint(source, checkpointId))
            .flatMap(
              _.fold[Result[Checkpoint]](notFound(source, checkpointId))(c => owned(source, c, config).map(_ => c))
            )
      }
    def vacant: Result[Unit] =
      checkpointer.latest(target).flatMap {
        case None => Right(())
        case Some(stored) =>
          checkTenant(target, stored, config).flatMap(_ => Left(GraphError.ThreadExists(target.value)))
      }
    if source == target then
      Left(ValidationError("target", "a fork creates a new thread; name one other than its source"))
    else
      origin.flatMap { from =>
        holding(target, config)(vacant) { claim =>
          // checked again under the claim: another runtime may have created the target in between
          vacant.flatMap { _ =>
            val now = clock.instant()
            val forked = from
              .withFormatVersion(Checkpoint.CurrentFormat)
              .withId(GraphRuntime.checkpointId(config.runId, 0L, 1L))
              .withParent(None)
              .withThreadId(target.value)
              .withRunId(config.runId.value)
              .withCreatedAt(now)
              .withTenantId(tenant)
              .withFencingToken(claim.token)
            val event = EventDraft(
              config.runId.value,
              Some(forked.id),
              None,
              None,
              now,
              RunEvent.ThreadForked(source.value, checkpointId, tenant, config.principal.map(_.value))
            )
            commitAndDeliver(target, Commit(claim.token, Some(forked), events = Vector(event))).left
              .map(GraphError.CheckpointWriteFailed(target.value, _))
              .map(_ => forked)
          }
        }
      }
  }

  /**
   * Applies `update` to the committed state of `threadId`'s latest checkpoint, which must be
   * `expected` - the version the caller read; [[GraphError.CheckpointConflict]] otherwise, so an
   * update never lands on a state its caller has not seen - and commits the result as a new latest
   * checkpoint, recorded with a [[RunEvent.StateUpdated]] event, and returns it. Use it to correct a
   * thread's state, for example before `resume`.
   *
   * Only the state changes: the new checkpoint keeps the status, scheduled tasks, open joins and
   * parked interrupts, so the same call - `start`, `recover` or `resume` - continues the thread
   * afterwards. With `asNode`, the update is checked as if that node had returned it: every key must
   * be in its declared write set ([[GraphError.UndeclaredWrite]]), and the event names the node.
   * Without it, every key must be one the graph registers ([[GraphError.UnknownStateKey]]). The
   * update is applied with each key's update function, like any node's ([[GraphError.StateUpdateFailed]]).
   *
   * The pending writes of tasks that completed against `expected` are carried over to the new
   * checkpoint, so `recover` still does not run them again; their updates apply after this one. The new
   * checkpoint is one superstep on from `expected`, so the supersteps a thread runs keep increasing and
   * no later model call derives a tool idempotency key an earlier one used.
   *
   * The thread is held - in this runtime and by a claim in the store, so the commit is fenced - while it
   * is updated, and a run holding it refuses the update with [[GraphError.ThreadBusy]]. A thread of
   * another tenant is [[GraphError.TenantMismatch]]; a thread with no checkpoint
   * [[GraphError.CheckpointNotFound]]. Every refusal leaves the thread unchanged.
   */
  def updateState[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    expected: String,
    update: StateUpdate,
    asNode: Option[NodeRef[?]] = None,
    config: RunConfig = RunConfig()
  ): Result[Checkpoint] = admission(threadId) {
    def edited: Result[(StoredCheckpoint, Execution)] =
      checkpointer.latest(threadId).flatMap {
        case None => notFound(threadId, expected)
        case Some(stored) =>
          for
            _ <- checkTenant(threadId, stored, config)
            _ <- Either.cond(
              stored.checkpoint.id == expected,
              (),
              GraphError.CheckpointConflict(threadId.value, Some(expected), Some(stored.checkpoint.id))
            )
            execution <- graph.restore(stored.checkpoint.snapshot)
            // the carried writes must still rebind: they are reused by the next `recover`
            _    <- reusableWrites(graph, execution, stored)
            edit <- graph.edit(execution, update, asNode)
          yield stored -> edit
      }
    holding(threadId, config)(edited.map(_ => ())) { claim =>
      // read again under the claim: the version, and the pending writes to carry over, are now settled
      edited.flatMap { (stored, execution) =>
        graph.snapshot(execution).flatMap { snapshot =>
          val now      = clock.instant()
          val tenant   = config.tenantId.map(_.value)
          val nodeId   = asNode.map(_.id.value)
          val parentId = stored.checkpoint.id
          val saved = Checkpoint(
            Checkpoint.CurrentFormat,
            GraphRuntime.checkpointId(config.runId, GraphRuntime.ordinal(Some(parentId)), 1L),
            Some(parentId),
            threadId.value,
            config.runId.value,
            stored.checkpoint.status,
            now,
            snapshot,
            tenant,
            Some(claim.token)
          )
          val event = EventDraft(
            config.runId.value,
            Some(saved.id),
            None,
            nodeId,
            now,
            RunEvent.StateUpdated(parentId, nodeId, tenant, config.principal.map(_.value))
          )
          commitAndDeliver(
            threadId,
            Commit(claim.token, Some(saved), stored.pendingWrites.map(_.copy(checkpointId = saved.id)), Vector(event))
          ).left
            .map {
              case conflict: GraphError.CheckpointConflict => conflict
              case other                                   => GraphError.CheckpointWriteFailed(threadId.value, other)
            }
            .map(_ => saved)
        }
      }
    }
  }

  /**
   * Removes the checkpoints and events of `threadId` that `policy` does not keep, by the store's clock
   * ([[Checkpointer.prune]]): never the latest checkpoint or its pending writes, so what the thread
   * does next is unchanged. Pruning a checkpoint also removes earlier events; `maxAge` and `maxEvents`
   * may remove more, including the latest checkpoint's. Removing events raises the replay floor
   * ([[GraphError.ReplayUnavailable]]). A thread of another tenant is [[GraphError.TenantMismatch]];
   * an unknown thread is `Right(())`. It needs no claim, so it may run while a run executes.
   */
  def prune(threadId: ThreadId, policy: RetentionPolicy, config: RunConfig = RunConfig()): Result[Unit] =
    admission(threadId) {
      checkpointer
        .latest(threadId)
        .flatMap(_.fold[Result[Unit]](Right(()))(checkTenant(threadId, _, config)))
        .flatMap(_ => checkpointer.prune(threadId, policy))
    }

  private def notFound[A](threadId: ThreadId, checkpointId: String): Result[A] =
    Left(GraphError.CheckpointNotFound(threadId.value, checkpointId))

  /** A checkpoint read from the history belongs to the caller's tenant, as its thread's latest does. */
  private def owned(threadId: ThreadId, checkpoint: Checkpoint, config: RunConfig): Result[Unit] =
    val requested = config.tenantId.map(_.value)
    Either.cond(checkpoint.tenantId == requested, (), GraphError.TenantMismatch(threadId.value, requested))

  /**
   * Holds `threadId` for one change that is not a run - in this runtime, then by a claim in the store -
   * and gives both back however `body` ends. `check` runs first, after the in-process hold and before
   * the store claim, so that a caller it refuses (another tenant) learns nothing from a claim; `body`
   * then runs with the claim, and should check again what it relies on. The claim is renewed from its
   * grant while `body` runs, as a run's is, so a slow store does not let it lapse; a loss needs no
   * action, since `body`'s commit is fenced by the claim and so refused.
   */
  private def holding[A](threadId: ThreadId, config: RunConfig)(check: => Result[Unit])(
    body: RunClaim => Result[A]
  ): Result[A] =
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
          check
            .flatMap { _ =>
              val sentAt = ticker()
              claimThread(threadId, config).map(_ -> sentAt)
            }
            .flatMap { (claim, sentAt) =>
              var heartbeat = Option.empty[Heartbeat]
              Using.resource(new AutoCloseable {
                def close(): Unit =
                  heartbeat.foreach(_.close())
                  releaseClaim(threadId, claim.token)
              }) { _ =>
                heartbeat = Some(
                  Heartbeat(
                    threadId,
                    config.runId,
                    claim.token,
                    sentAt,
                    "its change is refused if not yet committed",
                    _ => ()
                  )
                )
                body(claim)
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
   * It runs with the interrupt flag clear, and sets it again afterwards: a cancelled or expired run
   * still has its flag set as it ends, and a store whose I/O honours interrupts would refuse the
   * release, leaving the thread busy for `ttl`.
   */
  private def releaseClaim(threadId: ThreadId, token: FencingToken): Unit =
    val interrupted = Thread.interrupted()
    releasing(threadId, token)
    if interrupted then Thread.currentThread().interrupt()

  /**
   * Gives back the claim whose token is `token` on a detached virtual thread, `llm4s-release-<threadId>`,
   * waiting for it at most `wait`: for a run whose end must not depend on the store answering - it lost
   * its claim, or a renewal of it could not be joined. Best effort: a release that never lands leaves
   * the claim to lapse after `claims.ttl`. A release is by token, so one that lands late cannot release
   * a claim another run has taken since. Never throws, and keeps the caller's interrupt flag.
   */
  private def releaseDetached(threadId: ThreadId, token: FencingToken, wait: Long): Unit =
    val releaser =
      Thread.ofVirtual().name(s"llm4s-release-${threadId.value}").start(() => releasing(threadId, token))
    if joinWithin(releaser, System.nanoTime() + wait, Thread.interrupted()) then Thread.currentThread().interrupt()

  /**
   * Joins `t` until `deadline` (a `System.nanoTime` value), even if interrupted; whether an interrupt
   * arrived, counting `interrupted`.
   */
  @tailrec private def joinWithin(t: Thread, deadline: Long, interrupted: Boolean): Boolean =
    val remaining = deadline - System.nanoTime()
    if remaining <= 0 || !t.isAlive then interrupted
    else
      CancelledError.catchInterrupt(t.join(java.time.Duration.ofNanos(remaining))) match
        case Right(_) => joinWithin(t, deadline, interrupted)
        case Left(_)  => joinWithin(t, deadline, interrupted = true)

  /**
   * Forgets the hub's hand-over mark for `threadId` unless a run holds the thread in this runtime: for a
   * late write of a run that has ended. Checked under `activeLock`, so it never races an admission.
   */
  private def forgetIfIdle(threadId: ThreadId): Unit =
    withLock(activeLock)(if !active.contains(threadId.value) then hub.release(threadId))

  private def releasing(threadId: ThreadId, token: FencingToken): Unit =
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
                // the store claim first - its heartbeat stopped and joined before it - then this
                // runtime's hold: once a caller sees the result, any runtime may claim the thread
                def releaseBoth(): Unit =
                  run.releaseClaim()
                  release(threadId)
                // from now on a lost claim stops the run as a cancel does; before, it was only recorded
                run.stopper = () => handle.stop(StopCause.ClaimLost)
                handle.launch(() => run.execute(), run.crashed, () => releaseBoth(), run.deadline)
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

  /** Whether a run is admitting or executing on the thread in this runtime; for tests. */
  private[graph] def heldLocally(threadId: ThreadId): Boolean = withLock(activeLock)(active.contains(threadId.value))

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
     * Told when the store refuses a submitted commit as stale - proof that another run took the thread
     * over - so the run stops at once rather than at its next superstep. Set by the run; Sync and Async
     * call it from the thread that made the commit.
     */
    @volatile var onStale: GraphError.StaleClaim => Unit = _ => ()

    /** Records `error` as the commit failure, unless one is recorded, and reports a stale refusal. */
    protected def refused(failed: AtomicReference[Option[LLMError]], error: LLMError): Unit =
      failed.compareAndSet(None, Some(error)): Unit
      error match
        case stale: GraphError.StaleClaim => onStale(stale)
        case _                            => ()

    /**
     * Forgets a failed commit, so that the next is tried. Called only when the run is stopped,
     * because the stop's interrupt can fail a synchronous commit on the run thread; a commit that
     * fails again is reported as before. Async and OnExit commits are never made on an
     * interruptible run thread, so they keep their failure.
     */
    def forgetInterruptedFailure(): Unit = ()

    /** Makes everything submitted durable (or fails), and releases resources. */
    def close(): Unit

    /**
     * Drops every commit not yet written, and fails with `reason` from then on: the run lost its
     * claim, so nothing more it submitted may land - after a takeover the store would refuse it, and
     * after a bare expiry it would record work of a run that has stopped. Waits for a commit already
     * in flight, as `close` does. Never throws.
     */
    def abandon(reason: LLMError): Unit

  final private class SyncCommitter(threadId: ThreadId) extends Committer:
    private val failed = AtomicReference[Option[LLMError]](None)
    def submit(commit: Commit): Unit =
      if failed.get.isEmpty then commitAndDeliver(threadId, commit).left.foreach(refused(failed, _))
    def failure: Option[LLMError]                 = failed.get
    def close(): Unit                             = ()
    override def forgetInterruptedFailure(): Unit = failed.set(None)
    def abandon(reason: LLMError): Unit           = failed.set(Some(reason))

  final private class AsyncCommitter(threadId: ThreadId) extends Committer:
    private val failed = AtomicReference[Option[LLMError]](None)

    /** Set by [[abandon]]: a commit that lands after it may land after the run has ended. */
    private val abandoned = new AtomicBoolean(false)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable =>
      val thread = new Thread(runnable, s"llm4s-graph-writer-${threadId.value}")
      thread.setDaemon(true)
      thread
    }
    def submit(commit: Commit): Unit =
      writer.execute { () =>
        // a failed commit stops the queue: a later commit must not land on top of a lost one
        if failed.get.isEmpty then
          commitAndDeliver(threadId, commit).left.foreach(refused(failed, _))
          // a write the run abandoned, landing after the run let the thread go, must leave the hub no
          // hand-over mark for a thread no run holds
          if abandoned.get then forgetIfIdle(threadId)
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

    /**
     * A queued commit finds the failure and skips itself; only the one in flight may land. It is waited
     * for at most `claims.ttl`: the run lost its claim, so its end must not wait on a store that hangs.
     * A commit still in the store then is left to finish on the writer, a daemon thread. It is fenced by
     * the run's token, so it lands only if no other run has claimed the thread meanwhile - the store
     * refuses it as stale otherwise - and its events, if it lands, are delivered in order under
     * `commitLock` like any other commit's. Never throws; an interrupt is remembered and set again.
     */
    def abandon(reason: LLMError): Unit =
      failed.set(Some(reason))
      abandoned.set(true)
      abandoning(threadId)
      writer.shutdown()
      val deadline = System.nanoTime() + claims.ttl.toNanos
      @tailrec def await(interrupted: Boolean): Boolean =
        val remaining = deadline - System.nanoTime()
        if remaining <= 0 then interrupted
        else
          CancelledError.catchInterrupt(writer.awaitTermination(remaining, TimeUnit.NANOSECONDS)) match
            case Right(_) => interrupted
            case Left(_)  => await(interrupted = true)
      if await(Thread.interrupted()) then Thread.currentThread().interrupt()

  final private class OnExitCommitter(threadId: ThreadId) extends Committer:
    private var durableParent: Option[Option[String]] = None
    private var checkpoint: Option[Checkpoint]        = None
    private var writes: Vector[PendingWrite]          = Vector.empty
    private var events: Vector[EventDraft]            = Vector.empty
    private var failed: Option[LLMError]              = None
    private var token: Option[FencingToken]           = None
    private var retract                               = false
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
      retract = retract || commit.retractTurn
    }
    def failure: Option[LLMError] = withLock(lock)(failed)
    def close(): Unit = withLock(lock) {
      val last = checkpoint.map(_.withParent(durableParent.flatten))
      token.foreach { fence =>
        if last.isDefined || writes.nonEmpty || events.nonEmpty then
          failed = commitAndDeliver(threadId, Commit(fence, last, writes, events, retract)).left.toOption
      }
    }
    def abandon(reason: LLMError): Unit = withLock(lock) {
      checkpoint = None
      writes = Vector.empty
      events = Vector.empty
      retract = false
      failed = Some(reason)
    }

  /**
   * Renews a claim from its grant until closed, and judges the claim's lease itself, so that a run
   * learns it has lost its claim even when no renewal reaches the store.
   *
   * The lease ends `claims.ttl` after the moment, by [[ticker]] (the runtime's monotonic clock), just
   * before the request that granted the claim, or that renewed it, was sent: the store started its own
   * `ttl` no earlier than that, so the run's lease ends no later than the store's, provided the store judges
   * expiry by one clock that does not run faster than the runtime's and is not stepped forward (a constant
   * offset does not matter). It is conservative by the time a request takes to reach the store. A store
   * clock that runs ahead can expire the claim early; the run then learns of it from a refused renewal or
   * commit.
   *
   * Two virtual threads, so that a renewal hung in the store cannot hide that the lease has passed. The
   * beat, `llm4s-claim-<threadId>`, never waits on the store: it wakes every `claims.renewEvery` - or
   * at the lease's end, if that is sooner - and declares the claim lost once the lease has passed;
   * otherwise, if no renewal is in flight, it starts one on `llm4s-claim-renew-<threadId>`. A renewal
   * that lands moves the lease's end. One refused with [[GraphError.StaleClaim]] - another run took the
   * thread over - declares the claim lost at once. Any other failure, returned or thrown (a store clock
   * that throws, say), is logged and tried again at the next beat, for as long as the lease lasts.
   * `consequence` says, in the loss's WARN log, what follows for the claim's holder.
   * `lost` is called at most once, with the current claim's token when the store named one, and never
   * after the heartbeat is closed.
   *
   * The lease's end and whether the heartbeat is over change only together, under `lock`: a landed
   * renewal publishes its lease only while the heartbeat is not over, and the beat declares the lease
   * passed only against the lease published at that moment, reading the clock before it takes the lock.
   * So a renewal published before the beat's decision always counts, and one published after it - or
   * after a loss found otherwise, or `close` - is ignored. The beat never reads the lease's end
   * outside the lock to decide, and nothing slow (the store, the clock, `lost`) runs under it.
   *
   * Closing stops the beat and waits for it and for a renewal in flight - no longer than `claims.ttl`
   * in all, so a store that hangs cannot hang the end of a run - so that no renewal lands after the
   * claim is released. A renewal still blocked then is left to finish on its own: its outcome is
   * ignored, and it can extend the claim only if the release that follows fails. Closing is
   * idempotent, never throws, and keeps the closing thread's interrupt flag.
   */
  final private class Heartbeat(
    threadId: ThreadId,
    holder: RunId,
    token: FencingToken,
    grantSentAt: Long,
    consequence: String,
    lost: Option[FencingToken] => Unit
  ) extends AutoCloseable:
    private val stopped = new CountDownLatch(1)

    /** Guards `over` and `leaseEnd` together; held only to read or change them. */
    private val lock = new java.util.concurrent.locks.ReentrantLock()

    /** Set once the claim is found lost or the heartbeat closes; written under `lock`. */
    @volatile private var over = false

    /** When the lease ends, as a [[ticker]] value; written under `lock`. */
    private var leaseEnd = grantSentAt + claims.ttl.toNanos

    /** The renewal in flight, if any: started only by the beat, and read by `close` once the beat ended. */
    private val renewal = AtomicReference[Option[Thread]](None)
    private val beat    = Thread.ofVirtual().name(s"llm4s-claim-${threadId.value}").start(() => beating())

    /** Ends the heartbeat if `when` holds of the lease's end, atomically with any renewal's publication. */
    private def end(when: Long => Boolean): Boolean =
      withLock(lock) {
        val ending = !over && when(leaseEnd)
        if ending then over = true
        ending
      }

    /** Reports the loss; only after [[end]] returned `true`, so once. Outside `lock`. */
    private def lose(current: Option[FencingToken], why: String): Unit =
      GraphRuntime.logger.warn(
        s"Run '${holder.value}' lost its claim on thread '${threadId.value}': $why; $consequence"
      )
      DefaultRunHandle.guarded(lost(current)): Unit

    /** How long the lease has left at `now`, or `None` once the heartbeat is over. */
    private def remainingAt(now: Long): Option[Long] = withLock(lock)(Option.when(!over)(leaseEnd - now))

    @tailrec private def beating(): Unit =
      // the clock is read first: the decision below is against the lease published when it is made
      val now = ticker()
      if end(ends => now - ends >= 0) then lose(None, s"its lease of ${claims.ttl} passed with no renewal landing")
      else
        remainingAt(now) match
          case None => ()
          case Some(remaining) =>
            DefaultRunHandle.guarded(
              stopped.await(math.max(1L, math.min(claims.renewEvery.toNanos, remaining)), TimeUnit.NANOSECONDS)
            ) match
              case Right(false) =>
                if !over && renewal.get.forall(!_.isAlive) then renewal.set(Some(renewing()))
                beating()
              // closed; nothing else wakes or interrupts it
              case _ => ()

    private def renewing(): Thread =
      Thread.ofVirtual().name(s"llm4s-claim-renew-${threadId.value}").start { () =>
        val sentAt = ticker()
        DefaultRunHandle.guarded(checkpointer.renew(threadId, token, claims.ttl)) match
          case Right(Right(_)) =>
            // the lease runs from when the request was sent, never from when its answer arrived
            val renewed = sentAt + claims.ttl.toNanos
            withLock(lock)(if !over && renewed - leaseEnd > 0 then leaseEnd = renewed)
          case Right(Left(stale: GraphError.StaleClaim)) =>
            if end(_ => true) then lose(stale.current.map(FencingToken(_)), stale.message)
          case Right(Left(error)) =>
            if !over then
              GraphRuntime.logger.warn(
                s"Renewing run '${holder.value}''s claim on thread '${threadId.value}' failed: ${error.message}"
              )
          case Left(thrown) =>
            if !over then
              GraphRuntime.logger.warn(
                s"Renewing run '${holder.value}''s claim on thread '${threadId.value}' failed",
                thrown
              )
        renewalSettled(threadId)
      }

    def close(): Unit = stop(): Unit

    /** [[close]], returning whether the beat and any renewal in flight were joined in time. */
    def stop(): Boolean =
      withLock(lock) { over = true }
      stopped.countDown()
      val deadline = System.nanoTime() + claims.ttl.toNanos
      // the beat first, so that no renewal starts after the one read below
      val interrupted = joinWithin(beat, deadline, Thread.interrupted())
      val all         = renewal.get.fold(interrupted)(joinWithin(_, deadline, interrupted))
      if all then Thread.currentThread().interrupt()
      !beat.isAlive && renewal.get.forall(!_.isAlive)

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
    private var checkpoints = 0L

    /** The `n` of the checkpoint this run claims over; its own checkpoints count on from it. */
    private var parentOrdinal = 0L

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
     * `carry` is called once the store claim is granted, and returns the pending writes to carry over
     * and the task results `execute` reuses: read under the claim, nothing a fenced holder writes
     * can be missed. `event` is the claim's event, given the granted claim.
     *
     * The claim's [[Heartbeat]] starts as soon as it is granted, so the first renewal is timed from the
     * grant, and the time admission takes counts against the lease. A loss found before the run is
     * launched is only recorded, as [[StopCause.ClaimLost]], and the run stops before its first superstep.
     */
    def admit(
      execution: Execution,
      parent: Option[String],
      event: RunClaim => RunEvent,
      carry: () => Result[(Vector[PendingWrite], Map[TaskId, TaskResult])] = () => Right(Vector.empty -> Map.empty)
    ): Result[Run[I, O]] =
      val sentAt = ticker()
      claimThread(threadId, config).flatMap { granted =>
        claim = Some(granted)
        parentOrdinal = GraphRuntime.ordinal(parent)
        var admitted = false
        Using.resource(new AutoCloseable { def close(): Unit = if !admitted then releaseClaim() }) { _ =>
          heartbeat = Some(
            Heartbeat(
              threadId,
              runId,
              granted.token,
              sentAt,
              "it stops and commits nothing more",
              claimLost
            )
          )
          val outcome =
            carry().flatMap((carried, reused) => committed(execution, parent, event(granted), carried, reused))
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
    private val claimReleased                     = new AtomicBoolean(false)

    /** Renews the claim, from its grant until [[releaseClaim]] stops it. */
    @volatile private var heartbeat: Option[Heartbeat] = None

    /** The thread's current claim, when the store named it as this run's claim was found lost. */
    private val lostTo = AtomicReference[Option[FencingToken]](None)

    /**
     * What the heartbeat does when it finds the claim lost: until the run has a handle, it records
     * [[StopCause.ClaimLost]], which the loop checks before every superstep; the runtime then sets it to
     * the handle's `stop`, which also interrupts the run, as a cancel does.
     */
    @volatile var stopper: () => Unit = () => cause.compareAndSet(None, Some(StopCause.ClaimLost)): Unit

    /** Records the current claim the store named, and stops the run through [[stopper]]. */
    private def claimLost(current: Option[FencingToken]): Unit =
      if current.nonEmpty then lostTo.compareAndSet(None, current): Unit
      stopper()

    // a commit refused as stale stops the run at once, as the heartbeat's loss does
    committer.onStale = stale => claimLost(stale.current.map(FencingToken(_)))

    /** The token every commit of this run carries; set by [[admit]] before its first commit. */
    private def token: FencingToken = claim.map(_.token).getOrElse(FencingToken(0L))

    /**
     * Stops the heartbeat - waiting for a renewal in flight, so that none lands after the release - and
     * gives back the store claim, once; a no-op before the claim was granted. Never throws, and keeps
     * the caller's interrupt flag.
     *
     * A run that lost its claim, or whose renewal could not be joined within `claims.ttl`, must not let
     * its end wait on the store - which may be hung, and may serialise the release behind that renewal -
     * so it releases on a detached thread: waiting for it up to `claims.ttl` if the heartbeat was joined,
     * so that a healthy store still frees the thread at once, and not at all otherwise.
     */
    def releaseClaim(): Unit =
      val joined = heartbeat.forall(_.stop())
      claim.foreach(c =>
        if claimReleased.compareAndSet(false, true) then
          if joined && !endedLost then GraphRuntime.this.releaseClaim(threadId, c.token)
          else releaseDetached(threadId, c.token, if joined then claims.ttl.toNanos else 0L)
      )

    /** Set once the run ends because it lost its claim. */
    @volatile private var endedLost = false

    /** Runs the claimed execution to its end; on the run thread. */
    def execute(): RunResult[O] =
      val (execution, checkpointId) = position
      // an interrupt thrown from anywhere in the loop, such as joining a superstep's tasks
      CancelledError.catchInterrupt(loop(execution, checkpointId, claimedReused)).fold(_ => cancelled(), identity)

    /**
     * Ends a run that `thrown` escaped from: the committer is closed as far as it can be, and the
     * run fails where it was, recording nothing more. It records [[StopCause.Finishing]] and
     * acknowledges first, so no later cancel, expiry or lost claim interrupts the close or the release.
     * A run that had already lost its claim drops what it had not written instead, and ends
     * [[GraphError.ClaimLost]]; `thrown` is logged.
     */
    def crashed(thrown: Throwable): RunResult[O] =
      cause.compareAndSet(None, Some(StopCause.Finishing)): Unit
      signal.acknowledge()
      if cause.get.contains(StopCause.ClaimLost) then
        GraphRuntime.logger.warn(
          s"Run '${runId.value}' on thread '${threadId.value}' crashed after losing its claim",
          thrown
        )
        lostClaim(position._1, None)
      else
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
        // the store refused a commit as stale: another run took the thread over
        case Some(stale: GraphError.StaleClaim) => lostClaim(execution, stale.current.map(FencingToken(_)))
        // a commit the stop's interrupt broke: the run was stopped, not failed by its store
        case Some(_) if stopRecorded => cancelled()
        // recorded as Finishing first, as `fail` does, so no later cancel, expiry or lost claim
        // interrupts the close or the release; a stop recorded since the check above is closed instead
        case Some(_) if !finishing() => cancelled()
        case Some(error)             => stop(execution, GraphError.CheckpointWriteFailed(threadId.value, error))
        case None =>
          position = execution -> checkpointId
          // the claim was found lost before the run could be interrupted: during admission, say
          if cause.get.contains(StopCause.ClaimLost) then lostClaim(execution, None)
          else if execution.paused || execution.isQuiescent then end(execution, checkpointId)
          else if execution.superstep - firstSuperstep >= config.budgets.maxSupersteps then
            fail(execution, GraphError.SuperstepLimitExceeded(config.budgets.maxSupersteps))
          else if Thread.currentThread().isInterrupted || overdue() then cancelled()
          else
            val outcomes = graph
              .executorFor(config.budgets)
              .runAll(execution.frontier.map { task => () =>
                reused.get(task.id) match
                  case Some(result) => Right(task -> result)
                  // held at a breakpoint before it runs: nothing to record, and recovery decides again
                  case None if graph.holdsBefore(task, config) => Right(task -> TaskResult.Held)
                  case None                                    => runTask(task, execution, checkpointId).map(task -> _)
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
              completed.flatMap(graph.commitSuperstep(execution, _, config)) match
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

    /** Whether a cancel, expiry or lost claim has been recorded, and so may have interrupted a commit. */
    private def stopRecorded: Boolean =
      cause.get.exists(c => c == StopCause.Cancelled || c == StopCause.Expired || c == StopCause.ClaimLost)

    /**
     * Records [[StopCause.Finishing]] unless a cause is already recorded, and acknowledges it, so that
     * no stop interrupts the run's closing; whether it was recorded.
     */
    private def finishing(): Boolean =
      val recorded = cause.compareAndSet(None, Some(StopCause.Finishing))
      if recorded then signal.acknowledge()
      recorded

    /**
     * A store failure as the run reports it: a commit refused as stale is [[GraphError.ClaimLost]],
     * anything else [[GraphError.CheckpointWriteFailed]], keeping `runError` as the run's own failure.
     */
    private def writeFailed(storeError: LLMError, runError: Option[LLMError]): LLMError = storeError match
      case stale: GraphError.StaleClaim =>
        // ClaimLost carries no run error: the failure the run was recording is logged instead
        runError.foreach(e =>
          GraphRuntime.logger.warn(
            s"Run '${runId.value}' on thread '${threadId.value}' lost its claim while recording its failure: ${e.message}"
          )
        )
        GraphError.ClaimLost(threadId.value, token, stale.current.map(FencingToken(_)).orElse(lostTo.get))
      case lost: GraphError.ClaimLost => lost
      case other                      => GraphError.CheckpointWriteFailed(threadId.value, other, runError)

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
              committer.failure.fold(result)(e => RunResult.Failed(execution.state, writeFailed(e, None)))

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
        case Right(saved)                                                      =>
          // the blocked turn leaves the history with it: nothing of it stays readable or forkable
          submit(saved, RunEvent.RunFailed(error.message), retractTurn = true)
          committer.close()
          committer.failure.fold[RunResult[O]](RunResult.Failed(execution.state, error))(e =>
            RunResult.Failed(execution.state, writeFailed(e, Some(error)))
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
              // a held task never reaches here: encodeWrite refuses it
              case TaskResult.Done(_) | TaskResult.Held => RunEvent.TaskCompleted
              case TaskResult.Parked(_, _, _)           => RunEvent.TaskSuspended(task.id.value)
              case TaskResult.Blocked(_, e)             => RunEvent.TaskFailed(e.message)
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
        val id = GraphRuntime.checkpointId(runId, parentOrdinal, checkpoints)
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

    private def submit(saved: Checkpoint, event: RunEvent, retractTurn: Boolean = false): Unit =
      committer.submit(
        Commit(token, Some(saved), Vector.empty, Vector(draft(Some(saved.id), None, event)), retractTurn)
      )

    /**
     * Ends a cancelled, expired or claim-lost run, by its recorded cause: [[RunEvent.RunCancelled]] and
     * [[GraphError.Cancelled]], or [[RunEvent.RunTimedOut]] and [[GraphError.DeadlineExceeded]]; a lost
     * claim ends as [[lostClaim]] does, committing nothing. The interrupt is cleared so the closing
     * commits can complete, then set again before returning, so the caller still sees it. A
     * synchronous commit that the interrupt failed is forgotten, so the closing commits are tried; if
     * they fail too, the run reports `CheckpointWriteFailed`. The checkpoint stays `Running`, and the
     * error names it - the last durable checkpoint, which `recover` continues from, also under OnExit,
     * whose exit commit keeps its id.
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
      val result = cause.get.getOrElse(StopCause.Cancelled) match
        case StopCause.ClaimLost => lostClaim(execution, None)
        // `Finishing` here means an interrupt from inside the run reached the outcome's commit
        case StopCause.Cancelled | StopCause.Finishing =>
          closeStopped(execution, RunEvent.RunCancelled, GraphError.Cancelled(Some(threadId.value), Some(checkpointId)))
        case StopCause.Expired =>
          closeStopped(execution, RunEvent.RunTimedOut, GraphError.DeadlineExceeded(threadId.value, Some(checkpointId)))
      Thread.currentThread().interrupt()
      result

    /** Commits a stopped run's closing `event`, against its last durable checkpoint, and fails it with `error`. */
    private def closeStopped(execution: Execution, event: RunEvent, error: LLMError): RunResult[O] =
      committer.forgetInterruptedFailure()
      committer.submit(Commit(token, None, Vector.empty, Vector(draft(Some(position._2), None, event))))
      stop(execution, error)

    /**
     * Ends a run that lost its claim: its lease passed with no renewal landing, a renewal was refused,
     * or the store refused a commit as stale. It commits nothing more - not even `RunFailed` - and its
     * committer drops what it had not written ([[Committer.abandon]]): after a takeover the store would
     * refuse it, and after a bare expiry it would record work of a run that has stopped. It fails with
     * [[GraphError.ClaimLost]], naming the current claim when the store named it. `ClaimLost` is
     * recorded and acknowledged first, unless another cause was, so no later stop interrupts the release.
     */
    private def lostClaim(execution: Execution, current: Option[FencingToken]): RunResult[O] =
      cause.compareAndSet(None, Some(StopCause.ClaimLost)): Unit
      signal.acknowledge()
      endedLost = true
      val lost = GraphError.ClaimLost(threadId.value, token, current.orElse(lostTo.get))
      committer.abandon(lost)
      RunResult.Failed(execution.state, lost)

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
        case (_, Some(storeError))                          => writeFailed(storeError, Some(error))
        case (_, None)                                      => error
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
