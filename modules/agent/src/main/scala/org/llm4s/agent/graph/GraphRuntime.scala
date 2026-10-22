package org.llm4s.agent.graph

import org.llm4s.agent.delegation.ChildRef
import org.llm4s.error.{ CancelledError, LLMError, ValidationError }
import org.llm4s.llmconnect.model.UsageSummary
import org.llm4s.types.{ Result, TryOps }
import org.slf4j.LoggerFactory

import java.time.{ Clock, Instant }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.{ CountDownLatch, ExecutorService, Executors, RejectedExecutionException, TimeUnit }
import scala.annotation.tailrec
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Try, Using }
import scala.util.control.Exception.catching

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
   * waits for the queue before returning, and fails if any queued commit failed - or with
   * [[GraphError.CommitTimedOut]] if one stays in the store for twice the claim's `ttl` with none returning.
   */
  case Async

  /**
   * After the claim, nothing is written until the run ends; then the last checkpoint, its pending
   * writes and every buffered event are committed at once. A crash loses the run's progress. An exit
   * commit that stays in the store for twice the claim's `ttl` ends the run [[GraphError.CommitTimedOut]].
   */
  case OnExit

/** Runs compiled graphs on durable threads; see [[GraphRuntime]]. */
object GraphRuntime:
  private val logger = LoggerFactory.getLogger(classOf[GraphRuntime])

  /** How many children a cascade or retention reads from the store at a time. */
  private val ChildPage: Int = 256

  /**
   * `config` for a run of a child thread (design 4.42): a child keeps at least its newest event, so the terminal
   * event of an attempt that committed nothing survives until the next `recoverChild` folds its usage - its
   * retention's `maxEvents` is at least 1. Otherwise its retention is the one it was given. That floor does not hold
   * off `maxAge`: what does is that a child's admission prunes only after the claim commit storing the fold (`Run.admit`).
   */
  private[agent] def childConfig(config: RunConfig): RunConfig =
    config.withRetention(config.retention.map { policy =>
      if policy.maxEvents.exists(_ < 1) then policy.withMaxEvents(1) else policy
    })

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

  /**
   * A runtime over a new [[InMemoryCheckpointer]], holding its runs' claims by `claims`. Both use `clock`: the
   * runtime stamps checkpoints and events with it, and the store judges claim expiry and [[GraphRuntime!.prune prune]]'s ages by
   * it, so a test that moves `clock` moves both.
   *
   * A [[GraphRuntime]] over a store of its own has two clocks: its `clock` stamps what it writes - each
   * checkpoint's `createdAt` and each event's timestamp - while the store's clock judges expiry and
   * retention ([[Checkpointer.prune]] compares the store's now with `createdAt`). Give both the same clock,
   * or keep hosts' clocks synchronised: skew between them shifts what `prune(maxAge)` keeps.
   */
  def inMemory(clock: Clock = Clock.systemUTC(), claims: ClaimPolicy = ClaimPolicy.default): GraphRuntime =
    new GraphRuntime(new InMemoryCheckpointer(clock), clock, claims)

  /** For callers that cannot use default arguments (Java, Kotlin): [[ClaimPolicy.default]]. */
  def inMemory(clock: Clock): GraphRuntime = inMemory(clock, ClaimPolicy.default)

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
 * A thread whose latest checkpoint records a [[RunOrigin]] is a child thread, started by a tool call of its parent's
 * run (design 4.41). It is driven only through that run: `start`, `recover`, `resume`, `fork`, `updateState` and
 * `deleteThread` on it are refused with [[GraphError.ChildThread]], after the tenant check and before any claim, while
 * `history`, `checkpoint`, `subscribe` and [[children]] read it as any thread. Deleting a thread deletes its children
 * first, and child retention ([[RunConfig.withChildRetention]]) bounds how many settled children a thread keeps. Each
 * run's terminal (`Completed` or `Failed`) checkpoint records how it settled ([[Checkpoint.outcome]]).
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
 * [[GraphError.ClaimLost]]. A loss found once the run has begun committing its outcome sends no interrupt,
 * but still skips every commit not yet begun; and whatever the run is doing, it ends within twice `ttl`
 * of the loss: if it has not begun to by then - its thread stuck in a store call that ignores interrupts -
 * it is ended without its thread, `ClaimLost`, and the thread freed. Admission is bounded the same way: a
 * claim's commit still in the store twice `ttl` after the loss is given up, and the call fails `ClaimLost` -
 * and if that commit then lands before the claim's release does, nobody having taken the thread over, it
 * leaves its `Running` checkpoint and its `RunStarted`, `RunRecovered` or `RunResumed` event although no run
 * continues it: `recover` does, once the release lands;
 * one the store refuses as stale - the claim lapsed, or another run took it over, before it landed - fails
 * `ClaimLost` too, never [[GraphError.CheckpointWriteFailed]]. So are [[fork]], [[updateState]] and
 * [[deleteThread]], which hold a claim while they change the thread: a write not begun at the loss is
 * never sent, and the call returns [[GraphError.ThreadBusy]] within twice `ttl` of it. A `recover` that takes over an expired claim records
 * the run it took the thread from ([[RunEvent.RunRecovered]]). A call on a thread whose run is still
 * executing in this runtime fails with `ThreadBusy` before reading the thread at all.
 *
 * Admission - the checks above, restoring the thread and committing the claim - runs on the
 * caller's thread, but for the read under the claim and the claim's commit, which run on
 * `llm4s-admit-<threadId>` while the caller waits, and never throws: a `Left` means no run exists, and the thread is free again. A
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
 * A run's closing wait is bounded while it holds its claim too (#1919). An `Async` run waiting for its queued
 * commits, or an `OnExit` run making its exit commit, whose commit stays in the store for twice `claims.ttl` with
 * none returning, ends [[GraphError.CommitTimedOut]] without waiting for it, through the same settling as a lost
 * run: its result is set, its thread freed in this runtime, and its claim released on a detached thread. The commit
 * is an orphan, fenced by the claim as a lost run's late write is: it lands only if the store applies it before the
 * release, and the run's own listeners never receive it. A drain that keeps making progress is never cut short,
 * however long it takes in all. Every other store call is waited for as long as it takes: every
 * [[Checkpointer]] must return from each call within a bound it documents.
 *
 * A run whose `RunBudgets.timeout` is set has a deadline, fixed when its claim commits; its tasks read it, by the
 * runtime's `clock`, as [[RunContext.deadline]]. When it passes, the run stops the same way, but ends with [[GraphError.DeadlineExceeded]] and commits
 * [[RunEvent.RunTimedOut]]. The first of cancel and expiry to be recorded wins, so a run reports
 * exactly one. `DeadlineExceeded` is recoverable: `recover` with a new budget continues the run.
 *
 * `subscribe` replays a thread's committed events after a sequence number and then delivers new
 * ones as their commits succeed - only to a caller of the thread's tenant, as every other read of a
 * thread is (see [[subscribe]]) - in ascending order with no gaps or duplicates, followed by live
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
 * sharing the checkpointer, with the same durable guarantees (ascending `seq`, no gap or duplicate):
 * each live subscription watches the store ([[WatchPolicy]], [[Checkpointer.awaitEventsAfter]]), so
 * they arrive as soon as the store reports them - at once from a store that is told of commits,
 * within `pollInterval` from one that is polled. Live progress events are never stored, so another
 * runtime's do not arrive.
 *
 * '''Ordering across runtimes.''' Concurrently active subscriptions to a thread that stay caught up see one
 * interleaving of durable and live events within one runtime: every event handed to several of them has the same
 * relative placement for each. A subscription that attaches later misses earlier live events, and a slower one can lose
 * live events ([[StreamEvent.LiveGap]]) or be disconnected as `Lagging`; the guarantee does not cover those cases. A
 * commit made elsewhere reaches each subscription through that subscription's own store watch, through a hand-over's
 * gap read, or, while the subscription is still in its initial replay (one created after the commit included), directly
 * from that replay, so its position among this runtime's live events can differ from one subscription to another; each still
 * sees the durable events in `seq` order. The guarantee holds when nothing outside this runtime commits to the thread;
 * an external commit allows subscriptions to place it differently, not necessarily make them differ. A consumer that
 * needs one order across runtimes orders only [[StreamEvent.Durable]] records by `EventRecord.seq`. It ignores
 * [[StreamEvent.Live]] and [[StreamEvent.LiveGap]], which have no `seq`, and treats [[StreamEvent.Disconnected]] as the
 * end of the subscription, not as a record: it carries `lastSeq` and a reason.
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

  /**
   * Every commit is written and handed to the event hub under its thread's lock, and the hub's store
   * watches take it too; a commit hung in the store holds up only its own thread's commits.
   */
  private val commitLocks = ThreadLocks()
  private val hub         = EventHub(checkpointer, commitLocks, watch)

  /**
   * Threads with a run admitting or executing in this runtime, each with that run's tenant, so a
   * caller from another tenant is refused even before the thread's first checkpoint exists, and its
   * run id, the holder `ThreadBusy` names; guarded by `activeLock`.
   */
  private val active     = mutable.Map.empty[String, (Option[String], RunId)]
  private val activeLock = new java.util.concurrent.locks.ReentrantLock()

  /**
   * Commits in flight that their run gave up on ([[Committer.orphan]]), by thread; guarded by
   * `activeLock`. While a thread has one, the hub keeps its hand-over mark even with no run holding the
   * thread, so that a subscription made meanwhile joins with a mark, and never waits for that commit
   * - possibly hung in the store - to learn what has been handed over ([[release]]).
   */
  private val orphans = mutable.Map.empty[String, Int]

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

  /** A test hook given each Async committer's writer thread as it is created; a no-op by default. */
  @volatile private[graph] var writerCreated: (ThreadId, Thread) => Unit = (_, _) => ()

  /** A test hook run by a claim's renewal thread once it has acted on the store's answer; a no-op by default. */
  @volatile private[graph] var renewalSettled: ThreadId => Unit = _ => ()

  /**
   * A test hook run by a heartbeat being stopped, once its beat has ended, just before it waits for a
   * renewal still in flight; a no-op by default.
   */
  @volatile private[graph] var joiningRenewal: ThreadId => Unit = _ => ()

  /**
   * A test hook run by a committer once a commit has begun - it was not fenced - and before it enters
   * the store; a no-op by default.
   */
  @volatile private[graph] var committing: (ThreadId, Commit) => Unit = (_, _) => ()

  /**
   * A test hook run by a change's worker ([[holding]]) once the change's body has returned - whether or
   * not its caller was given it; a no-op by default.
   */
  @volatile private[graph] var changeReturned: ThreadId => Unit = _ => ()

  /**
   * A test hook run by [[Committer.fence]] when it fences the committer, with whether a commit had
   * begun and not yet returned then; a no-op by default.
   */
  @volatile private[graph] var fenced: (ThreadId, Boolean) => Unit = (_, _) => ()

  /**
   * The claims this runtime failed to give back ([[releaseClaim]]), by thread, so that
   * [[deleteThread]] can try once more before it claims the thread: the store refuses a live claim
   * whoever asks, the same run id included, so a one-shot run whose release failed could not
   * otherwise be forgotten until its claim lapsed. A claim granted on the thread since drops it.
   * Entries are not expired: each is a thread id and a token, one per thread whose release failed
   * and that this runtime has neither deleted nor claimed since. A thread's entry only ever moves to a
   * newer token ([[recordUnreleased]]), and a release that lands removes it only if it is its own.
   */
  // a Scala map, whose `remove` returns an `Option`: a Java map's `null` for a missing key, typed as the
  // opaque `FencingToken` (a `Long`), may be unboxed to token 0 before `Option(...)` sees it
  private val unreleased = scala.collection.concurrent.TrieMap.empty[String, FencingToken]

  /**
   * Replays events with `seq > afterSeq`, then delivers new events until cancelled or
   * disconnected. The subscription is scoped to the thread, so it delivers every later run on it,
   * and its dispatcher (a virtual thread, parked while idle) lives until [[Subscription.cancel]];
   * see there for how cancel waits on a running listener. Commits made through this runtime are
   * handed over as they land; those of another runtime or process sharing the checkpointer are read
   * from the store by the subscription's watch ([[WatchPolicy]]: a second virtual thread, which
   * `cancel` stops too), in `seq` order and with the same durable guarantees, and are queued only as
   * they fit, so they never make it lag. Where such a commit falls among this runtime's live events
   * is not fixed: it depends on which read reached this subscription first (its initial replay, its watch, or a
   * hand-over's gap read), so
   * two subscriptions can place it differently. The guarantee that caught-up subscriptions in one runtime see one
   * live/durable interleaving holds while nothing outside the runtime commits to the thread; an external commit
   * allows it to differ rather than making it differ. Where one order across runtimes
   * matters, order only [[StreamEvent.Durable]] records by `seq`, ignoring [[StreamEvent.Live]] and
   * [[StreamEvent.LiveGap]], and end at [[StreamEvent.Disconnected]] (#1920). Live progress of another runtime's runs is not delivered: it is
   * never stored. With [[WatchPolicy.disabled]], another runtime's commits are seen only by
   * subscribing again, which replays the log - all of it once no commit a run of this runtime gave up
   * on is still in the store on the thread; until then - for as long as the store's bound on a call
   * lets that write take ([[Checkpointer]]) - the replay stops at the first event above what this runtime has handed over. A failed read of the store ends the subscription,
   * after what is queued, with [[DisconnectReason.ReplayFailed]]. Returns without
   * replaying: replay runs on the subscription's dispatcher thread, the only thread `listener` is
   * called on, and a failed replay ends it with [[DisconnectReason.ReplayFailed]]. The subscription
   * receives every live event sent after this returns: one sent while it replays is held - up to `capacity`, the rest
   * dropped and counted as a [[StreamEvent.LiveGap]] - and delivered after the durable events
   * committed before it and before those committed after it, as live events are once the replay is
   * done. A listener that throws is disconnected
   * ([[DisconnectReason.ListenerFailed]]). `capacity` bounds durable and live events separately,
   * so live events never crowd out durable ones, and must be at least two: a live event is queued
   * only while two live slots are free, one being reserved for the [[StreamEvent.LiveGap]] marker
   * that precedes it, so with one slot no live event could ever be accepted.
   *
   * A subscription reads the thread, so it is tenant-checked as [[history]] is: a thread whose latest
   * checkpoint is another tenant's than `config.tenantId` - or, for a thread with no checkpoint yet,
   * whose run admitting or executing in this runtime is - is refused with [[GraphError.TenantMismatch]]
   * before the subscription joins. A read of the thread that fails is returned as it is; one that throws
   * is [[GraphError.RunCrashed]], as for `history`. A thread can still change hands under its id once
   * subscribed: one with no checkpoint yet is created by whichever tenant runs first, and one deleted
   * through another runtime can be created again before the subscription learns of the delete. So
   * every event is screened before it is delivered - by the tenant the event names, for the events a
   * run, a fork or a state update begins with, and otherwise, until the first event delivered has
   * confirmed the thread as the caller's, by its latest checkpoint's, read again then - and an event of another tenant's thread ends the
   * subscription before it with `Disconnected(lastSeq, DisconnectReason.TenantMismatch)`. A thread
   * with no checkpoint any more ends it with `ThreadDeleted`, and a failed read with `ReplayFailed`.
   */
  def subscribe(
    threadId: ThreadId,
    afterSeq: Long = 0L,
    capacity: Int = 1024,
    config: RunConfig = RunConfig(),
    nested: Boolean = false
  )(
    listener: StreamEvent => Unit
  ): Result[Subscription] = admission(threadId) {
    val requested = config.tenantId.map(_.value)
    validCapacity(capacity).flatMap { _ =>
      checkpointer
        .latest(threadId)
        .flatMap {
          // the checkpoint decides: a run reserved in this runtime may be another tenant's call that its
          // own admission is about to refuse, and must not make the owner's subscription fail
          case Some(stored) => checkTenant(threadId, stored, config)
          // with none yet, a run admitting or executing here is the only owner there is: it has passed
          // every check a new thread has, and will create the thread for its tenant
          case None =>
            val holderTenant = withLock(activeLock)(active.get(threadId.value).map(_._1))
            Either.cond(
              holderTenant.forall(_ == requested),
              (),
              GraphError.TenantMismatch(threadId.value, requested)
            )
        }
        .flatMap(_ =>
          // unconfirmed even when the checkpoint matched: the thread can change hands before the first
          // event is read, so the first event delivered is screened against the store again
          dispatched(
            threadId,
            afterSeq,
            capacity,
            listener,
            AbandonedWrites.none,
            TenantScope.Of(requested, confirmed = false),
            nested
          )
        )
    }
  }

  /**
   * [[subscribe]], as the hub's subscription, which a run's handle can give its end-of-run barrier;
   * `scope` is whose thread it may deliver.
   */
  private def dispatched(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit,
    abandoned: EventRecord => Boolean,
    scope: TenantScope,
    nested: Boolean
  ): Result[Dispatched] =
    validCapacity(capacity).flatMap(_ =>
      hub.subscribe(threadId, afterSeq, capacity, listener, abandoned, scope, nested)
    )

  private def validCapacity(capacity: Int): Result[Unit] =
    Either.cond(
      capacity >= 2,
      (),
      ValidationError("capacity", s"must be at least 2 (one slot is reserved for a LiveGap), was $capacity")
    )

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
  ): Result[RunHandle[O]] = startOn(threadId, graph, input, config, durability, None, observer, None)

  /**
   * Starts a run on `threadId` only if the thread is new: an existing thread of the caller's tenant
   * is refused with `existing`, no run started. Checked during admission, under the thread's
   * exclusivity and against the claim's parent checkpoint, so no other run can create the thread
   * between the check and the claim. Another tenant's thread is still `TenantMismatch` first. `amend`'s update is
   * written with the thread's first checkpoint, as [[startChild]]'s is.
   */
  private[agent] def startNew[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    config: RunConfig,
    existing: => LLMError,
    observer: Option[Observer] = None,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty)
  ): Result[RunHandle[O]] =
    startOn(threadId, graph, input, config, Durability.Sync, Some(() => existing), observer, None, amend = amend)

  /**
   * Starts the first run of the child thread `threadId`, recording `origin` - its `startedAt` stamped now, by the
   * runtime's clock - on its first checkpoint and every later one (design 4.41): the only way a child thread is created. The thread must be new - an existing one is
   * [[GraphError.ThreadExists]], checked as [[startNew]] checks - and the run is admitted as any other, with the
   * parent's tenant in `config`. For the child runner (K2), which derives `threadId` with [[ThreadId.child]].
   *
   * `parentClaim` is the token of the parent run's claim on `origin.parent`: the child's first commit, which writes the
   * origin and so registers the child, is fenced by it in the store ([[Commit.fence]], #2071). A parent claim taken
   * over or released meanwhile refuses that commit, writing nothing, and the start is [[GraphError.ClaimLost]] on the
   * parent, the child's own claim given back.
   */
  private[agent] def startChild[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    origin: RunOrigin,
    config: RunConfig,
    observer: Option[Observer] = None,
    notAfter: Option[Instant] = None,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty),
    parentClaim: Option[FencingToken] = None
  ): Result[RunHandle[O]] =
    startOn(
      threadId,
      graph,
      input,
      GraphRuntime.childConfig(config),
      Durability.Sync,
      Some(() => GraphError.ThreadExists(threadId.value)),
      observer,
      Some(origin),
      notAfter,
      amend,
      parentClaim.map(origin.parent -> _)
    )

  private def startOn[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    input: I,
    config: RunConfig,
    durability: Durability,
    newOnly: Option[() => LLMError],
    observer: Option[Observer],
    origin: Option[RunOrigin],
    notAfter: Option[Instant] = None,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty),
    parentFence: Option[(ThreadId, FencingToken)] = None
  ): Result[RunHandle[O]] = exclusively(threadId, config, observer) { admitting =>
    // a top-level start sets the thread's metadata (design 4.52) in its claim commit; a child's never does
    val metadata =
      if origin.isDefined || config.metadata.isEmpty then Right(None)
      else ThreadMetadata.of(config.metadata).map(Some(_))
    graph
      .checkBreakpoints(config)
      .flatMap(_ => metadata)
      .flatMap(m => checkpointer.latest(threadId).map(m -> _))
      .flatMap {
        case (metadata, None) =>
          // a child's origin records when it was first admitted, by this runtime's clock
          newRun(
            graph,
            threadId,
            config,
            durability,
            0,
            admitting,
            origin.map(_.withStartedAt(clock.instant())),
            notAfter
          )
            // the child's first commit is fenced by its parent's claim (#2071)
            .admit(
              graph.start(input),
              None,
              _ => started(config),
              amend = amended(_, amend),
              fence = parentFence,
              metadata = metadata
            )
        case (metadata, Some(stored)) =>
          // the child runner's start of a thread that exists is ThreadExists, whatever the thread is
          val admissible = if origin.isDefined then Right(()) else notChild(threadId, stored)
          checkTenant(threadId, stored, config)
            .flatMap(_ => admissible)
            .flatMap(_ => notDeleting(threadId, stored))
            .flatMap { _ =>
              newOnly match
                case Some(refusal) => Left(refusal())
                case None =>
                  stored.checkpoint.status match
                    case CheckpointStatus.Running =>
                      Left(GraphError.IncompleteRun(threadId.value, stored.checkpoint.id))
                    case CheckpointStatus.Suspended => Left(pendingInterrupts(threadId, stored))
                    case CheckpointStatus.Completed | CheckpointStatus.Failed =>
                      graph.restoreAtRest(stored.checkpoint.snapshot).flatMap { done =>
                        newRun(graph, threadId, config, durability, done.superstep, admitting, None)
                          .admit(
                            graph.startAt(done.superstep, done.state, input),
                            Some(stored.checkpoint.id),
                            _ => started(config),
                            // a settled parent's calls never run again (a new turn makes new keys), so retention may
                            // delete its older settled children and settle abandoned ones (design 4.41), under this run's
                            // claim: it finishes a pass a non-durable last superstep skipped, and removes children a
                            // Block's retraction left
                            retainChildren = true,
                            metadata = metadata
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
  ): Result[RunHandle[O]] = recoverOn(threadId, graph, config, durability, observer, child = false)

  /**
   * [[recover]] on a child thread (design 4.41), which `recover` refuses: for the child runner (K2), reattaching a
   * replayed call to the child its first run started. A top-level thread is refused with a `ValidationError`.
   *
   * `amend` is given the thread's state under the new run's claim, before retention and the claim's commit, and the
   * update it returns commits with that claim's checkpoint (design 4.42): the child runner folds the usage of the
   * attempts that committed nothing into the child's prior usage there, so a crash before the commit folds nothing and
   * one after it leaves the fold recorded once. Reading events is up to `amend` ([[storedEvents]]).
   */
  private[agent] def recoverChild[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    config: RunConfig,
    observer: Option[Observer] = None,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty),
    notAfter: Option[Instant] = None,
    parentClaim: Option[FencingToken] = None
  ): Result[RunHandle[O]] =
    recoverOn(
      threadId,
      graph,
      GraphRuntime.childConfig(config),
      Durability.Sync,
      observer,
      child = true,
      amend,
      notAfter,
      parentClaim
    )

  private def recoverOn[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    config: RunConfig,
    durability: Durability,
    observer: Option[Observer],
    child: Boolean,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty),
    notAfter: Option[Instant] = None,
    parentClaim: Option[FencingToken] = None
  ): Result[RunHandle[O]] = exclusively(threadId, config, observer) { admitting =>
    graph.checkBreakpoints(config).flatMap(_ => checkpointer.latest(threadId)).flatMap {
      case None => Left(GraphError.NothingToRecover(threadId.value))
      case Some(stored) =>
        checkTenant(threadId, stored, config)
          .flatMap(_ => childAs(threadId, stored, child))
          .flatMap(_ => notDeleting(threadId, stored))
          .flatMap { _ =>
            stored.checkpoint.status match
              case CheckpointStatus.Running =>
                for
                  execution <- graph.restoreStored(stored.checkpoint)
                  reused    <- reusableWrites(graph, execution, stored)
                  run <- newRun(
                    graph,
                    threadId,
                    config,
                    durability,
                    execution.superstep,
                    admitting,
                    stored.checkpoint.origin,
                    notAfter
                  ).admit(
                    execution,
                    Some(stored.checkpoint.id),
                    // a claim that replaced an expired one is a takeover: its run may still be executing, even
                    // when it had this run's id - a reused RunId names two runs, so the id is never filtered.
                    // The store names it only if it was a claim of this run's tenant (#1926)
                    granted =>
                      RunEvent.RunRecovered(
                        stored.checkpoint.id,
                        config.tenantId.map(_.value),
                        config.principal.map(_.value),
                        granted.takenOverFrom.map(_.value)
                      ),
                    // read again under the claim: a holder whose claim expired but was not yet replaced
                    // could still add pending writes after the read above, and only the claim stops it
                    () =>
                      checkpointer.latest(threadId).flatMap {
                        case Some(now) if now.checkpoint.id == stored.checkpoint.id =>
                          reusableWrites(graph, execution, now).map(now.pendingWrites -> _)
                        // the thread moved on: the claim commit's parent no longer matches, so it conflicts
                        case _ => Right(stored.pendingWrites -> reused)
                      },
                    amend = amended(_, amend),
                    // a child's reattachment is fenced by its parent's claim, as its first commit is (#2071)
                    fence = parentFenceOf(stored, parentClaim)
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
  ): Result[RunHandle[O]] = resumeOn(threadId, graph, answers, config, durability, observer, child = false)

  /**
   * [[resume]] on a child thread (design 4.41), which `resume` refuses: for the child runner (K2). A top-level
   * thread is refused with a `ValidationError`. `amend` adds an update to the resumed state, committed with the run's
   * claim, as [[recoverChild]]'s does (the child's grant, design 4.45).
   */
  private[agent] def resumeChild[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig,
    observer: Option[Observer] = None,
    notAfter: Option[Instant] = None,
    parentClaim: Option[FencingToken] = None,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty)
  ): Result[RunHandle[O]] =
    resumeOn(
      threadId,
      graph,
      answers,
      GraphRuntime.childConfig(config),
      Durability.Sync,
      observer,
      child = true,
      notAfter,
      parentClaim,
      amend
    )

  private def resumeOn[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig,
    durability: Durability,
    observer: Option[Observer],
    child: Boolean,
    notAfter: Option[Instant] = None,
    parentClaim: Option[FencingToken] = None,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty)
  ): Result[RunHandle[O]] = exclusively(threadId, config, observer) { admitting =>
    graph.checkBreakpoints(config).flatMap(_ => checkpointer.latest(threadId)).flatMap {
      case None => Left(GraphError.NotSuspended(threadId.value))
      case Some(stored) =>
        checkTenant(threadId, stored, config)
          .flatMap(_ => childAs(threadId, stored, child))
          .flatMap(_ => notDeleting(threadId, stored))
          .flatMap { _ =>
            if stored.checkpoint.status != CheckpointStatus.Suspended then Left(GraphError.NotSuspended(threadId.value))
            else
              for
                suspended <- graph.restoreStored(stored.checkpoint)
                resumed   <- graph.resume(suspended, answers)
                run <- newRun(
                  graph,
                  threadId,
                  config,
                  durability,
                  resumed.superstep,
                  admitting,
                  stored.checkpoint.origin,
                  notAfter
                ).admit(
                  resumed,
                  Some(stored.checkpoint.id),
                  _ =>
                    RunEvent.RunResumed(
                      suspended.parked.map(_.interrupt).filter(answers.contains).map(_.value),
                      config.tenantId.map(_.value),
                      config.principal.map(_.value)
                    ),
                  fence = parentFenceOf(stored, parentClaim),
                  amend = amended(_, amend)
                )
              yield run
          }
    }
  }

  /**
   * A top-level thread, or a child thread - its latest checkpoint records a [[RunOrigin]] - refused with
   * [[GraphError.ChildThread]]: a child thread is driven only through its parent's run (design 4.41).
   */
  private def notChild(threadId: ThreadId, stored: StoredCheckpoint): Result[Unit] =
    stored.checkpoint.origin.fold[Result[Unit]](Right(()))(origin =>
      Left(GraphError.ChildThread(threadId.value, origin.parent.value))
    )

  /**
   * A thread whose cascade delete began and did not finish - its latest checkpoint is [[Checkpoint.deleting]] - refused
   * with [[GraphError.ThreadDeleting]] (design 4.41, #2074): some of its descendants may be gone, so no run may replay
   * it. Only `deleteThread` proceeds on it.
   */
  private def notDeleting(threadId: ThreadId, stored: StoredCheckpoint): Result[Unit] =
    Either.cond(!stored.checkpoint.deleting, (), GraphError.ThreadDeleting(threadId.value))

  /** [[notChild]] for a top-level call; for a child runner's call (`child`), the thread must be a child thread. */
  private def childAs(threadId: ThreadId, stored: StoredCheckpoint, child: Boolean): Result[Unit] =
    if !child then notChild(threadId, stored)
    else
      Either.cond(
        stored.checkpoint.origin.isDefined,
        (),
        ValidationError("threadId", s"'${threadId.value}' is not a child thread")
      )

  /** The fence on a child's parent's claim ([[Commit.fence]], #2071): the parent its origin names, with `parentClaim`. */
  private def parentFenceOf(
    stored: StoredCheckpoint,
    parentClaim: Option[FencingToken]
  ): Option[(ThreadId, FencingToken)] =
    parentClaim.flatMap(token => stored.checkpoint.origin.map(_.parent -> token))

  /**
   * A store's refusal of a commit for its fence's claim, not the committing thread's, as [[GraphError.ClaimLost]] on
   * the fence thread: taken out before [[Held.committing]], which would act on a `StaleClaim` as a loss of its own.
   */
  private def fenceRefused(threadId: ThreadId, fence: Option[(ThreadId, FencingToken)])(error: LLMError): LLMError =
    (error, fence) match
      case (GraphError.StaleClaim(above, aboveToken, current), Some((other, _)))
          if other != threadId && other.value == above =>
        GraphError.ClaimLost(above, aboveToken, current)
      case _ => error

  /** `execution` with the update `amend` makes to its state, at the same superstep: [[recoverChild]]'s `amend`. */
  private def amended(execution: Execution, amend: ThreadState => Result[StateUpdate]): Result[Execution] =
    amend(execution.state).flatMap { update =>
      if update.operations.isEmpty then Right(execution)
      else execution.state.applyUpdate(update).map(execution.edited(_, execution.superstep))
    }

  /** The runtime's clock's current instant: what a child runner measures its parent's deadline against. */
  private[agent] def now(): Instant = clock.instant()

  /** How long a claim of this runtime lives without renewal ([[ClaimPolicy.ttl]]). */
  private[agent] def claimTtl: FiniteDuration = claims.ttl

  /**
   * Up to `limit` of `threadId`'s events after `afterSeq`, oldest first, straight from the store: for the child runner
   * (design 4.42), which reads a child's terminal events, under the child's claim or after its run ended.
   */
  private[agent] def storedEvents(threadId: ThreadId, afterSeq: Long, limit: Int): Result[Vector[EventRecord]] =
    checkpointer.eventsAfter(threadId, afterSeq, limit)

  /**
   * The child thread `threadId` as it rests (design 4.42): its latest checkpoint and that checkpoint's execution,
   * restored through `graph`; `None` for a thread with none. Tenant-checked as [[history]] is; needs no claim.
   */
  private[agent] def childAtRest[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    config: RunConfig
  ): Result[Option[(Checkpoint, Execution)]] = admission(threadId) {
    checkpointer.latest(threadId).flatMap {
      case None => Right(None)
      case Some(stored) =>
        checkTenant(threadId, stored, config)
          .flatMap(_ => graph.restoreStored(stored.checkpoint))
          .map(execution => Some(stored.checkpoint -> execution))
    }
  }

  /**
   * The id of the graph whose run wrote `threadId`'s latest checkpoint - for an agent, the agent's id - with no
   * restore; `None` for a thread with none. Tenant-checked as [[childAtRest]] is. For `BoundAgent.run` on a
   * caller-chosen thread (#2086), to name another agent's thread rather than report a failed restore.
   */
  private[agent] def storedGraphId(threadId: ThreadId, config: RunConfig): Result[Option[String]] =
    admission(threadId) {
      checkpointer.latest(threadId).flatMap {
        case None         => Right(None)
        case Some(stored) => checkTenant(threadId, stored, config).map(_ => Some(stored.checkpoint.snapshot.graphId))
      }
    }

  /** A suspended `execution` of `graph` as the run result a live run that suspended there returned. */
  private[agent] def suspendedResult[I, O](graph: CompiledGraph[I, O], execution: Execution): RunResult[O] =
    RunResult.Suspended(execution.state, graph.pendingInterrupts(execution), execution)

  /**
   * Settles the child thread `threadId` (design 4.42) as `outcome`, a failure: a `Failed` checkpoint over its latest,
   * with a [[RunEvent.RunFailed]] carrying `message`, under the child's own claim. Its state is the latest
   * checkpoint's, with the update `amend` makes to it under that claim (the child runner folds the usage of the
   * attempts that committed nothing into it). For the child runner under `ChildFailure.Report`, whose call commits
   * once the child is settled, so no child is left `Running` behind a committed call.
   *
   * A child already settled is left as it is. A busy child is [[GraphError.ThreadBusy]], and one with no checkpoint
   * [[GraphError.NothingToRecover]]; a top-level thread is refused with a `ValidationError`. With `parentClaim` the
   * commit is fenced by the parent's claim too (#2071): a parent claim taken over or released meanwhile writes nothing,
   * and is [[GraphError.ClaimLost]] on the parent, the child's claim given back.
   */
  private[agent] def settleChild[I, O](
    threadId: ThreadId,
    graph: CompiledGraph[I, O],
    outcome: RunOutcome,
    message: String,
    config: RunConfig,
    amend: ThreadState => Result[StateUpdate] = _ => Right(StateUpdate.empty),
    owns: Checkpoint => Boolean = _ => true,
    parentClaim: Option[FencingToken] = None
  ): Result[Unit] =
    def latest: Result[Option[StoredCheckpoint]] = checkpointer.latest(threadId)
    // checked before the claim, and again under it: the thread may have been deleted and recreated in between
    def valid(stored: StoredCheckpoint): Result[Unit] =
      checkTenant(threadId, stored, config)
        .flatMap(_ => childAs(threadId, stored, child = true))
        .flatMap(_ =>
          Either.cond(
            owns(stored.checkpoint),
            (),
            ValidationError("threadId", s"'${threadId.value}' is not the child thread being settled")
          )
        )
    val checked = latest.flatMap {
      case None         => Left(GraphError.NothingToRecover(threadId.value))
      case Some(stored) => valid(stored)
    }
    holding(threadId, config)(checked) { held =>
      latest.flatMap {
        case None                                 => Left(GraphError.NothingToRecover(threadId.value))
        case Some(stored) if valid(stored).isLeft => valid(stored)
        case Some(stored)
            if stored.checkpoint.status == CheckpointStatus.Completed ||
              stored.checkpoint.status == CheckpointStatus.Failed =>
          Right(())
        case Some(stored) =>
          val open = stored.checkpoint
          for
            execution <- graph.restoreStored(open)
            settled   <- amended(execution, amend)
            snapshot  <- graph.snapshot(settled)
            now = clock.instant()
            saved = Checkpoint(
              Checkpoint.CurrentFormat,
              GraphRuntime.checkpointId(config.runId, GraphRuntime.ordinal(Some(open.id)), 1L),
              Some(open.id),
              threadId.value,
              config.runId.value,
              CheckpointStatus.Failed,
              now,
              snapshot,
              open.tenantId,
              Some(held.claim.token),
              open.origin,
              Some(outcome)
            )
            event  = EventDraft(config.runId.value, Some(saved.id), None, None, now, RunEvent.RunFailed(message))
            fence  = parentFenceOf(stored, parentClaim)
            commit = Commit(held.claim.token, Some(saved), Vector.empty, Vector(event)).withFence(fence)
            _ <- held.committing(commit)(commitAndDeliver(threadId, commit).left.map(fenceRefused(threadId, fence)))
          yield ()
      }
    }

  /**
   * Whether run `runId` ended as a guardrail's Block ([[NodeResult.Block]]): the thread's latest checkpoint is that
   * run's closing one, its outcome [[RunOutcome.Blocked]]. An ordinary failure leaves no closing
   * checkpoint of its own, and a run started on the thread since makes this `false`.
   */
  private[agent] def endedBlocked(threadId: ThreadId, runId: RunId): Result[Boolean] =
    checkpointer
      .latest(threadId)
      .map(
        _.exists(s =>
          s.checkpoint.runId == runId.value && s.checkpoint.outcome.exists(_.isInstanceOf[RunOutcome.Blocked])
        )
      )

  /**
   * Deletes `threadId` from the runtime's store - its checkpoint, pending writes, event log and claim -
   * so its id names a new thread again, and before it every thread below it ([[children]], design 4.41): its
   * children's children before its children, each under its own claim, all of them held before any is deleted. Refused,
   * with nothing deleted, as admission refuses a run: a thread of another tenant is [[GraphError.TenantMismatch]], a
   * child thread [[GraphError.ChildThread]] (delete its parent), and one whose run - or a run of any thread below
   * it - is admitting or executing in this runtime, or holds a live claim in any runtime, is [[GraphError.ThreadBusy]].
   * A delete that fails part-way leaves the thread and fewer descendants, the thread marked
   * [[Checkpoint.deleting]] so that every admission refuses it with [[GraphError.ThreadDeleting]]; deleting again
   * finishes it. The thread is held exclusively, and claimed in the store under `config.runId`, while it is
   * deleted, so no run starts on it meanwhile; and the store's delete is fenced by that claim's
   * token, so if the claim lapses before the delete lands - this runtime stalled past
   * `claims.ttl` - and another run takes the thread over, nothing of that run is deleted: the call
   * is `ThreadBusy`, or `TenantMismatch` if the run is another tenant's. An unknown thread is
   * `Right(())`. The claim is renewed while the delete runs and given back as [[fork]]'s and
   * [[updateState]]'s are, if the delete does not happen. As theirs, a claim found lost bounds the
   * call: a delete not yet begun then is never sent, and is `ThreadBusy`; if the call is still waiting
   * on the store `2 × claims.ttl` after the loss it returns `ThreadBusy` naming no checkpoint, with the
   * thread freed, and a delete already in the store may land afterwards. The tenant is checked once more after the
   * store claim is granted, so a thread another
   * runtime created for another tenant in between is `TenantMismatch`, not deleted. A claim this
   * runtime failed to give back when a run on the thread ended is given back first, once the tenant
   * has been checked, so that it does not refuse the delete until it lapses.
   *
   * A subscription follows a thread, not its id. A [[subscribe]] in this runtime made before the
   * delete, or while it is in flight, ends with `Disconnected(lastSeq, DisconnectReason.ThreadDeleted)` once it has delivered
   * what it had queued. One in another runtime sharing the store ends the same way when it next reads
   * the store: when its watch next polls ([[WatchPolicy]]), or, with the watch disabled, only when
   * its own runtime next commits on the thread. A subscription in another runtime that has delivered
   * no event yet - it is still at `0` - cannot be told from a new one, and follows the new thread
   * instead - if it is its tenant's: one of another tenant ends with
   * `Disconnected(lastSeq, DisconnectReason.TenantMismatch)` before its first event ([[subscribe]]).
   * None of them delivers a new thread created under the same id as a continuation of the
   * deleted one: its events are numbered on past the deleted thread's
   * ([[Checkpointer.deleteThread]]); subscribe again from `0` to follow it.
   */
  def deleteThread(threadId: ThreadId, config: RunConfig = RunConfig()): Result[Unit] = admission(threadId) {
    deleteTree(threadId, config, child = false)
  }

  /**
   * Deletes `threadId` and, first, every thread below it (design 4.41): its children's children before its
   * children, then the thread. Every one of them is held - in this runtime and by a claim in the store - before any is
   * deleted, so a descendant with a run live anywhere refuses the whole delete ([[GraphError.ThreadBusy]]) with nothing
   * removed. Each delete is fenced by its own thread's claim; one that fails stops the cascade there, leaving the
   * thread and fewer descendants, and deleting again finishes it. Before the first descendant goes, the thread is
   * marked deleting under its claim ([[markDeleting]]), so a cascade stopped part-way leaves a thread every admission
   * refuses ([[GraphError.ThreadDeleting]]) rather than one whose replay re-runs a deleted child (#2074). `child` is whether `threadId` is a child thread
   * deleted by retention: a top-level delete refuses a child thread ([[GraphError.ChildThread]]), and retention only
   * deletes children.
   *
   * The cascade is fenced by the claims above what it deletes, so it never deletes a child that a successor run on
   * the thread above may reattach and would then run again (#2056 review, #2063). Before each delete it checks
   * `fence` - retention's check of the parent's claim ([[keepChildren]]) - and, for a descendant, `threadId`'s own
   * claim ([[Held.holds]]), stopping at the first that fails; and the store checks the claim above atomically with
   * each delete ([[Checkpointer.deleteThread]]'s `fence`), so a takeover between the check and the delete deletes
   * nothing: `above` - retention's parent and the parent's claim token - fences every delete of the tree, and
   * without it, as for a top-level delete, `threadId`'s claim fences each descendant's. A fence that fails is
   * [[GraphError.ThreadBusy]] on the thread above, with nothing more deleted.
   */
  private def deleteTree(
    threadId: ThreadId,
    config: RunConfig,
    child: Boolean,
    fence: () => Boolean = () => true,
    above: Option[(ThreadId, FencingToken)] = None
  ): Result[Unit] =
    def checked: Result[Unit] =
      checkpointer
        .latest(threadId)
        .flatMap(
          _.fold[Result[Unit]](Right(()))(stored =>
            checkTenant(threadId, stored, config).flatMap(_ => if child then Right(()) else notChild(threadId, stored))
          )
        )
    // a delete that happened removed the claim with the thread: there is nothing to give back
    holding(threadId, config, consumesClaim = (_: Result[Unit]).isRight) {
      checked.map { _ =>
        // a claim of this runtime's that it failed to give back - a run's, ended - would refuse the
        // delete until it lapsed; nothing holds the thread in this runtime now, so it is given back
        // first, once the caller is known to be the thread's tenant
        unreleased.remove(threadId.value).foreach(releaseClaim(threadId, _))
      }
    } { held =>
      // checked again under the claim: another runtime may have created the thread, for another
      // tenant, between the read above and the claim. The delete names no parent checkpoint, but it
      // is fenced by the claim, so nothing can be committed between this read and the delete unless
      // the claim was lost - and then the delete is refused. Under the claim no run of the thread can
      // start a child, so its descendants are settled from here on
      def fenced: Result[Unit] =
        Either.cond(fence(), (), GraphError.ThreadBusy(threadId.value, None, GraphError.BusyHolder.Lapsed))
      def rootHolds: Result[Unit] =
        held.holds.left.map {
          case stale: GraphError.StaleClaim => GraphError.ThreadBusy(threadId.value, None, afterLoss(stale.current))
          case other                        => GraphError.CheckpointWriteFailed(threadId.value, other)
        }
      checked
        .flatMap(_ => descendants(threadId))
        .flatMap(below =>
          holdingAll(below, config) { heldBelow =>
            // listed before they were held: a descendant's run may have made a child in between and let its
            // claim go. Only a run of its parent makes a child, so with every listed thread held the tree can
            // no longer grow; listed again now, a tree that grew refuses the delete rather than orphan the child
            val unchanged = descendants(threadId).flatMap(now =>
              Either.cond(now.toSet == below.toSet, (), GraphError.ThreadBusy(threadId.value, None))
            )
            // marked before the first descendant goes, so a cascade stopped part-way leaves a thread no admission
            // replays (#2074); a thread with no descendants is deleted in one store call and needs no marker. Nor does
            // a child retention deletes (`above`): it is prunable, so never admitted again whatever is left of it, and a
            // checkpoint commit cannot be fenced by the parent's claim, so a stale pass could otherwise mark it
            def marked: Result[Unit] =
              if heldBelow.isEmpty || above.isDefined then Right(())
              else fenced.flatMap(_ => rootHolds).flatMap(_ => markDeleting(held, config))
            unchanged
              .flatMap(_ => marked)
              .flatMap(_ =>
                heldBelow.foldLeft[Result[Unit]](Right(()))((done, child) =>
                  done
                    .flatMap(_ => fenced)
                    .flatMap(_ => rootHolds)
                    .flatMap(_ =>
                      // retention's parent fences the whole tree; otherwise this thread's claim, whose loss the store
                      // finds then is acted on as `rootHolds` would act on it
                      above.fold(deleteHeld(child, config, Some(threadId -> held.claim.token), held.foundLost))(fence =>
                        deleteHeld(child, config, Some(fence))
                      )
                    )
                )
              )
          }
        )
        .flatMap(_ => fenced)
        .flatMap(_ => deleteHeld(held, config, above))
    }

  /**
   * Marks the thread `held` holds as deleting (design 4.41, #2074): a copy of its latest checkpoint with
   * [[Checkpoint.deleting]] set, committed under its claim, so admission refuses it ([[GraphError.ThreadDeleting]])
   * from then until the delete finishes. A thread already marked - a cascade run again - or with no checkpoint is left
   * as it is. The copy keeps the status, snapshot and origin, so reads and the child index see the thread unchanged.
   */
  private def markDeleting(held: Held, config: RunConfig): Result[Unit] =
    val threadId = held.threadId
    checkpointer.latest(threadId).flatMap {
      case Some(stored) if !stored.checkpoint.deleting =>
        val parentId = stored.checkpoint.id
        val marker = stored.checkpoint
          .withFormatVersion(Checkpoint.CurrentFormat)
          .withId(GraphRuntime.checkpointId(config.runId, GraphRuntime.ordinal(Some(parentId)), 1L))
          .withParent(Some(parentId))
          .withRunId(config.runId.value)
          .withCreatedAt(clock.instant())
          .withFencingToken(held.claim.token)
          .withDeleting(true)
        val commit = Commit(held.claim.token, Some(marker))
        held
          .committing(commit)(checkpointer.commit(threadId, commit))
          .left
          .map(changeRefused(threadId, config))
          .map(_ => ())
      case _ => Right(())
    }

  /**
   * Deletes the thread `held` holds, fenced by its claim; see [[deleteThread]]. A claim that lapsed while this runtime
   * stalled, and that another run took over, deletes nothing of that run, and is refused as any live claim is; a
   * delete not begun when the claim was found lost never reaches the store, and is refused so. `fence` is the claim
   * above the thread that the store checks with the delete ([[deleteTree]]): one it refuses deleted nothing and is
   * [[GraphError.ThreadBusy]] on that thread, a loss of the claim above, not of `held`'s, reported to `aboveLost`.
   */
  private def deleteHeld(
    held: Held,
    config: RunConfig,
    fence: Option[(ThreadId, FencingToken)],
    aboveLost: Option[FencingToken] => Unit = _ => ()
  ): Result[Unit] =
    val threadId = held.threadId
    val claim    = held.claim
    // a refusal for the fence's claim is taken out before `committing`, which would act on it as a loss of `held`'s
    def fenceLost(error: LLMError): LLMError = (error, fence) match
      case (stale: GraphError.StaleClaim, Some((above, _))) if above != threadId && stale.threadId == above.value =>
        aboveLost(stale.current)
        GraphError.ThreadBusy(above.value, None, afterLoss(stale.current))
      case _ => error
    val outcome = held.committing(Commit(claim.token))(
      checkpointer.deleteThread(threadId, claim.token, fence).left.map(fenceLost)
    ) match
      case Left(stale: GraphError.StaleClaim) =>
        busyAt(threadId, config, holder = afterLoss(stale.current))
      case other => other
    // after the store delete, so a subscription that joined while it was in flight ends too - while
    // the thread is still held here, so no run of this runtime has begun on the next thread under
    // the id. A delete that landed after its caller was given up on and let the thread go is not
    // announced: a run may hold the thread again, and its subscriptions must not end. This
    // runtime's subscriptions to the deleted thread then learn of it from the store, as another
    // runtime's do
    if outcome.isRight then held.stillHeld(hub.threadDeleted(threadId)): Unit
    outcome

  /**
   * Holds every thread of `threads` - in this runtime, then by a claim in the store, one inside the other - and runs
   * `body` with them all held, in order; a thread that cannot be held refuses the whole call before `body` runs. Each
   * is checked first, as [[deleteTree]] checks a thread, and given back however `body` ends, unless deleted.
   */
  private def holdingAll(threads: Vector[ThreadId], config: RunConfig)(
    body: Vector[Held] => Result[Unit]
  ): Result[Unit] =
    threads.headOption match
      case None => body(Vector.empty)
      case Some(threadId) =>
        val check =
          checkpointer.latest(threadId).flatMap(_.fold[Result[Unit]](Right(()))(checkTenant(threadId, _, config)))
        holding(threadId, config, consumesClaim = (_: Result[Unit]).isRight)(check) { held =>
          holdingAll(threads.tail, config)(rest => body(held +: rest))
        }

  /** Every thread below `threadId`, deepest first: each child's descendants, then the child, in creation order. */
  private def descendants(threadId: ThreadId): Result[Vector[ThreadId]] =
    allChildren(threadId).flatMap { children =>
      children.foldLeft[Result[Vector[ThreadId]]](Right(Vector.empty)) { (acc, child) =>
        acc.flatMap(found => descendants(child.threadId).map(below => found ++ below :+ child.threadId))
      }
    }

  /** Every direct child of `threadId`, oldest first, read a page at a time. */
  private def allChildren(threadId: ThreadId): Result[Vector[ChildThread]] =
    @tailrec def pages(after: Option[ThreadId], found: Vector[ChildThread]): Result[Vector[ChildThread]] =
      checkpointer.children(threadId, after, GraphRuntime.ChildPage) match
        case Left(error)                                       => Left(error)
        case Right(page) if page.size < GraphRuntime.ChildPage => Right(found ++ page)
        case Right(page)                                       => pages(page.lastOption.map(_.threadId), found ++ page)
    pages(None, Vector.empty)

  /**
   * Child retention (design 4.41, rule 12): keeps the newest `config.childRetention` prunable children of `threadId` -
   * settled (`Completed` or `Failed`, so with an outcome) - and deletes each older one with everything below it
   * ([[deleteTree]]). Called only where every settled child is prunable - its call committed and will never run
   * again: under the parent's claim at `start` admission, and before a run's terminal checkpoint once its last
   * superstep is durable, or after it once an `Async` or `OnExit` run's commits drain ([[Run.retainBeforeSettling]]), or after a blocked run's closing checkpoint - never at `recover` or `resume` admission, when a child whose
   * call has not committed must stay for that call's replay. Idempotent. Housekeeping: a failure is logged at WARN.
   *
   * A `Running` or `Suspended` child for which `abandon` holds - at `start` admission, every one listed, since no call
   * of an earlier run can be replayed; at a terminal pass, those admission left busy - can never be reattached, so it
   * is first settled `RunOutcome.Failed("abandoned", ...)` under its own claim ([[abandonChild]]) and then counts; one
   * that is busy is skipped and returned, for the same run's terminal pass to revisit (#2073), and is otherwise left
   * to the next `start`. Any other `Running` or `Suspended` child - a child of the current run - is never touched.
   *
   * The judgment holds for the children that existed while the parent's latest checkpoint was `base`, the one the
   * caller judged by, and only while the caller holds the parent's claim. So the pass is fenced twice:
   *  - by the parent's claim: `fenced` - the claim not found lost, and its token still the store's current claim - is
   *    checked before the listing, after it, and before each child is settled or deleted (also inside that child's
   *    cascade), and the pass stops at the first mismatch, so a stale worker whose claim was lost never deletes a child
   *    that a successor run just made (#2056 review);
   *  - against a successor: the parent is read before and after the listing, and the pass touches nothing unless its
   *    latest is `base` both times - a successor run commits a checkpoint of its own before any of its calls starts a
   *    child, and checkpoint ids are never reused, so such a listing holds none of a successor's children.
   */
  private def keepChildren(
    threadId: ThreadId,
    config: RunConfig,
    base: Option[String],
    token: FencingToken,
    fenced: () => Boolean,
    abandon: ThreadId => Boolean
  ): Set[ThreadId] =
    def atBase: Boolean =
      Try(checkpointer.latest(threadId)).toResult.flatten.toOption.flatten.map(_.checkpoint.id) == base
    def skipped(): Unit =
      GraphRuntime.logger.debug(
        s"Retention on ${threadId.value} skipped: its claim was lost or the thread moved on from ${base.getOrElse("none")}"
      )
    if !fenced() || !atBase then
      skipped()
      Set.empty
    else
      Try(allChildren(threadId)).toResult.flatten match
        case Left(error) =>
          GraphRuntime.logger.warn(s"Listing the children of ${threadId.value} for retention failed: ${error.message}")
          Set.empty
        case Right(_) if !fenced() || !atBase =>
          skipped()
          Set.empty
        case Right(children) =>
          // once a fence fails the pass touches nothing more
          var stopped = false
          def holds(): Boolean =
            if !stopped && !fenced() then
              stopped = true
              skipped()
            !stopped
          val busy = Set.newBuilder[ThreadId]
          // in creation order, and lazily, so nothing is settled once a fence fails
          val prunable = children.iterator
            .filter { c =>
              c.status match
                case CheckpointStatus.Completed | CheckpointStatus.Failed => true
                case CheckpointStatus.Running | CheckpointStatus.Suspended =>
                  abandon(c.threadId) && holds() && {
                    val settled = abandonChild(c.threadId, threadId, token, config)
                    if !settled then busy += c.threadId
                    settled
                  }
            }
            .map(_.threadId)
            .toVector
          prunable.dropRight(config.childRetention).iterator.takeWhile(_ => holds()).foreach { old =>
            Try(deleteTree(old, config, child = true, () => holds(), Some(threadId -> token))).toResult.flatten.left
              .foreach(error =>
                GraphRuntime.logger.warn(
                  s"Deleting the child ${old.value} of ${threadId.value} for retention failed: ${error.message}"
                )
              )
          }
          busy.result()

  /**
   * Settles `child`, a `Running` or `Suspended` child of `parent` that no run can reattach ([[keepChildren]]), as
   * `RunOutcome.Failed("abandoned", ...)`: a `Failed` checkpoint over its latest, with the same snapshot and a
   * [[RunEvent.RunFailed]], under the child's own claim, fenced by the parent's, whose token is `token` (#2071).
   * Whether the child is now settled: one already settled is; one that is busy, gone, or whose commit fails - also
   * for the parent's claim - is not.
   */
  private def abandonChild(child: ThreadId, parent: ThreadId, token: FencingToken, config: RunConfig): Boolean =
    val message = s"abandoned: the run of parent thread '${parent.value}' that started it ended without it"
    val outcome = settleUnreachable(child, parent, token, RunOutcome.Failed("abandoned", message), message, config)
    outcome.left.foreach(error =>
      GraphRuntime.logger.debug(
        s"Settling the abandoned child ${child.value} of ${parent.value} skipped: ${error.message}"
      )
    )
    outcome.toOption.flatten.isDefined

  /**
   * Settles `child`, a `Running` or `Suspended` child of `parent` that no call will reattach, as `outcome`, a
   * `RunOutcome.Failed`: a `Failed` checkpoint over its latest, with the same snapshot and a [[RunEvent.RunFailed]]
   * carrying `message`, under the child's own claim, fenced by the parent's, whose token is `token` (#2071). Needs no
   * graph. The run that settled it: this settlement's, or the one that settled it first; `None` for a child with no
   * checkpoint. A busy child is [[GraphError.ThreadBusy]]; a parent claim lost meanwhile writes nothing. For retention's
   * abandon ([[abandonChild]]) and a timed-out delegating call's child (#2098).
   */
  private[graph] def settleUnreachable(
    child: ThreadId,
    parent: ThreadId,
    token: FencingToken,
    outcome: RunOutcome,
    message: String,
    config: RunConfig
  ): Result[Option[RunId]] =
    def latest: Result[Option[StoredCheckpoint]] = checkpointer.latest(child)
    Try(
      holding(child, config)(latest.flatMap(_.fold[Result[Unit]](Right(()))(checkTenant(child, _, config)))) { held =>
        latest.flatMap {
          case None => Right(None)
          case Some(stored)
              if stored.checkpoint.status == CheckpointStatus.Completed ||
                stored.checkpoint.status == CheckpointStatus.Failed =>
            Right(Some(RunId(stored.checkpoint.runId)))
          case Some(stored) =>
            val open = stored.checkpoint
            val now  = clock.instant()
            val saved = Checkpoint(
              Checkpoint.CurrentFormat,
              GraphRuntime.checkpointId(config.runId, GraphRuntime.ordinal(Some(open.id)), 1L),
              Some(open.id),
              child.value,
              config.runId.value,
              CheckpointStatus.Failed,
              now,
              open.snapshot,
              open.tenantId,
              Some(held.claim.token),
              open.origin,
              Some(outcome)
            )
            val event =
              EventDraft(config.runId.value, Some(saved.id), None, None, now, RunEvent.RunFailed(message))
            val fence  = Some(parent -> token)
            val commit = Commit(held.claim.token, Some(saved), Vector.empty, Vector(event)).withFence(fence)
            held
              .committing(commit)(commitAndDeliver(child, commit).left.map(fenceRefused(child, fence)))
              .map(_ => Some(config.runId))
        }
      }
    ).toResult.flatten

  /**
   * The parent's fence for a retention pass ([[keepChildren]]): its claim, whose token is `token`, is not found lost
   * here (`lost`), and is still the thread's current claim in the store, which a renewal proves
   * ([[Checkpointer.renew]]).
   */
  private def parentFence(threadId: ThreadId, token: FencingToken, lost: () => Boolean): () => Boolean =
    () => !lost() && Try(checkpointer.renew(threadId, token, claims.ttl)).toResult.flatten.isRight

  /**
   * Up to `limit` of `threadId`'s direct children (design 4.41), oldest first, starting after child `after` when it is
   * given: pass the last id of a page to read the next ([[Checkpointer.children]]). Tenant-checked as [[history]] is:
   * a thread of another tenant is [[GraphError.TenantMismatch]]. A thread with no checkpoint has no children; `limit`
   * must be positive (`ValidationError`). Allowed on a child thread, whose own children are listed. Needs no claim.
   */
  def children(
    threadId: ThreadId,
    after: Option[ThreadId] = None,
    limit: Int = 100,
    config: RunConfig = RunConfig()
  ): Result[Vector[ChildThread]] = admission(threadId) {
    if limit < 1 then Left(ValidationError("limit", s"must be at least 1, was $limit"))
    else
      checkpointer.latest(threadId).flatMap {
        case None => Right(Vector.empty)
        case Some(stored) =>
          checkTenant(threadId, stored, config).flatMap(_ => checkpointer.children(threadId, after, limit))
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
   * store while it is created; a fork whose claim lapses, or is taken over, before its commit lands is
   * `ThreadBusy` too, with nothing created - as is one whose claim is found lost before its commit
   * begins, which is then never sent. Once the claim is found lost, the call waits on the store at most
   * `2 × claims.ttl` after the loss: past that it returns `ThreadBusy` naming no checkpoint, with the
   * target freed, and a commit already in the store may land afterwards, fenced by the claim's token.
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
            .flatMap(_ => notChild(source, stored))
            .flatMap(_ => notDeleting(source, stored))
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
        holding(target, config)(vacant) { held =>
          val claim = held.claim
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
              // a fork is a top-level thread of its own
              .withOrigin(None)
            val event = EventDraft(
              config.runId.value,
              Some(forked.id),
              None,
              None,
              now,
              RunEvent.ThreadForked(source.value, checkpointId, tenant, config.principal.map(_.value))
            )
            val commit = Commit(claim.token, Some(forked), events = Vector(event))
            held
              .committing(commit)(commitAndDeliver(target, commit))
              .left
              .map(changeRefused(target, config))
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
   * is updated, and a run holding it refuses the update with [[GraphError.ThreadBusy]], as does a claim
   * that lapses, or is taken over, before the update's commit lands, or that is found lost before the
   * commit begins - the commit is then never sent. Once the claim is found lost, the call waits on the
   * store at most `2 × claims.ttl` after the loss: past that it returns `ThreadBusy` naming no checkpoint,
   * with the thread freed, and a commit already in the store may land afterwards. A thread of
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
            _ <- notChild(threadId, stored)
            _ <- notDeleting(threadId, stored)
            _ <- Either.cond(
              stored.checkpoint.id == expected,
              (),
              GraphError.CheckpointConflict(threadId.value, Some(expected), Some(stored.checkpoint.id))
            )
            execution <- graph.restoreStored(stored.checkpoint)
            // the carried writes must still rebind: they are reused by the next `recover`
            _    <- reusableWrites(graph, execution, stored)
            edit <- graph.edit(execution, update, asNode)
          yield stored -> edit
      }
    holding(threadId, config)(edited.map(_ => ())) { held =>
      val claim = held.claim
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
            Some(claim.token),
            stored.checkpoint.origin,
            // the status is kept, and with it how the run it records settled
            stored.checkpoint.outcome
          )
          val event = EventDraft(
            config.runId.value,
            Some(saved.id),
            None,
            nodeId,
            now,
            RunEvent.StateUpdated(parentId, nodeId, tenant, config.principal.map(_.value))
          )
          val commit =
            Commit(claim.token, Some(saved), stored.pendingWrites.map(_.copy(checkpointId = saved.id)), Vector(event))
          held
            .committing(commit)(commitAndDeliver(threadId, commit))
            .left
            .map {
              case conflict: GraphError.CheckpointConflict => conflict
              case other                                   => changeRefused(threadId, config)(other)
            }
            .map(_ => saved)
        }
      }
    }
  }

  /**
   * Replaces `threadId`'s metadata with `metadata` (design 4.52), which the thread registry searches by
   * ([[Checkpointer.threads]]); the thread's checkpoints and state are unchanged. It is a change, held and fenced as
   * [[updateState]] is: the thread is held in this runtime and claimed in the store, and the commit carries the claim's
   * token, so a run holding the thread, here or in another runtime, refuses it with [[GraphError.ThreadBusy]], as does
   * a claim that lapses before the commit lands. A thread of another tenant is [[GraphError.TenantMismatch]]; a child
   * thread [[GraphError.ChildThread]]; a thread whose cascade delete has begun [[GraphError.ThreadDeleting]]; a thread
   * with no checkpoint a `ValidationError` (start it first, with `RunConfig.metadata`). Every refusal leaves the
   * thread unchanged.
   */
  def updateMetadata(threadId: ThreadId, metadata: ThreadMetadata, config: RunConfig = RunConfig()): Result[Unit] =
    admission(threadId) {
      def checked: Result[Unit] =
        checkpointer.latest(threadId).flatMap {
          case None =>
            Left(ValidationError("threadId", s"thread '${threadId.value}' has no checkpoint: start it first"))
          case Some(stored) =>
            checkTenant(threadId, stored, config)
              .flatMap(_ => notChild(threadId, stored))
              .flatMap(_ => notDeleting(threadId, stored))
        }
      holding(threadId, config)(checked) { held =>
        // checked again under the claim: the thread may have been deleted or recreated meanwhile
        checked.flatMap { _ =>
          val commit = Commit(held.claim.token).withMetadata(metadata)
          held
            .committing(commit)(commitAndDeliver(threadId, commit))
            .left
            .map(changeRefused(threadId, config))
            .map(_ => ())
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

  /**
   * A store's refusal of a change's commit - `fork`'s or `updateState`'s - as the change reports it. One
   * refused as stale means the change's claim lapsed, or another run took the thread over, before the
   * commit landed: nothing was applied, and the change may be retried once the thread is free, so it is
   * [[GraphError.ThreadBusy]], as a claim refused up front is ([[claimThread]]), but saying whose: a store
   * that named the claim that replaced the change's gives [[GraphError.BusyHolder.Unnamed]], and one that
   * named none [[GraphError.BusyHolder.Lapsed]] - no other run is known to hold the thread ([[afterLoss]]).
   * A change is not a run, so it never ends [[GraphError.ClaimLost]]. The thread is re-read and its tenant checked first, so a caller
   * from another tenant gets `TenantMismatch` and learns nothing; the claim the store named is not, since
   * its holder's tenant is unknown. A re-read that fails or throws falls back to a `ThreadBusy` naming no
   * checkpoint. Any other refusal is [[GraphError.CheckpointWriteFailed]].
   */
  private def changeRefused(threadId: ThreadId, config: RunConfig)(error: LLMError): LLMError = error match
    case stale: GraphError.StaleClaim =>
      val holder = afterLoss(stale.current)
      Try(busyAt(threadId, config, holder)).toResult.flatten.fold(
        {
          case refusal @ (_: GraphError.TenantMismatch | _: GraphError.ThreadBusy) => refusal
          case _ => GraphError.ThreadBusy(threadId.value, None, holder)
        },
        identity
      )
    case other => GraphError.CheckpointWriteFailed(threadId.value, other)

  /**
   * Who holds a thread after a change's claim was lost, as [[GraphError.ThreadBusy]] reports it: another
   * run, unnamed, when the loss named the claim that replaced the change's (`current`); otherwise nobody
   * known - the change's own lease passed, or the store found no current claim.
   */
  private def afterLoss(current: Option[FencingToken]): GraphError.BusyHolder =
    if current.isDefined then GraphError.BusyHolder.Unnamed else GraphError.BusyHolder.Lapsed

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
   * then runs with the claim, and should check again what it relies on, and make its one write to the
   * store through [[Held.committing]]. The claim is renewed from its grant while `body` runs, as a
   * run's is, so a slow store does not let it lapse.
   *
   * A loss is acted on as a run's is. `body` runs on a virtual thread, `llm4s-change-<threadId>`, bounded
   * as an admission's claim commit is ([[boundedByLoss]]): once the heartbeat finds the claim lost, the
   * caller waits for `body` only until twice `claims.ttl` after the loss, and past that gets
   * [[GraphError.ThreadBusy]] naming no checkpoint, without `body` and without calling the store - so
   * that a store hung on a renewal, which a store serialising its calls queues `body`'s next call behind,
   * cannot hold the change or its thread. The loss fences `body`'s write, atomically with its start
   * ([[Committer.fence]]): one not yet begun never reaches the store, and is refused as a commit the
   * store refuses as stale ([[GraphError.StaleClaim]]), which `body` maps to `ThreadBusy`. A write the store
   * does refuse as stale is a loss found then, acted on as the heartbeat's is ([[Held.committing]]). One already in
   * the store may still land; a write given up on is counted as an orphan ([[Committer.orphan]]), so the
   * hub keeps its mark for the thread only until it returns.
   *
   * The claim is given back as a run's is ([[Run.releaseClaim]]): on a detached thread if it was lost,
   * or if a renewal could not be joined within `claims.ttl`, so that a store hung on that renewal cannot
   * hold the change's end. An outcome `consumesClaim` accepts - a delete that removed the claim with the
   * thread - gives nothing back: the heartbeat is halted, and the claim, already gone, is not released.
   */
  private def holding[A](
    threadId: ThreadId,
    config: RunConfig,
    consumesClaim: Result[A] => Boolean = (_: Result[A]) => false
  )(check: => Result[Unit])(body: Held => Result[A]): Result[A] =
    // compared by reference, so a late write can tell its own hold from a later one's ([[Held.stillHeld]])
    val reservation = (config.tenantId.map(_.value), config.runId)
    val holder = withLock(activeLock) {
      val existing = active.get(threadId.value)
      if existing.isEmpty then active.update(threadId.value, reservation)
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
              // when the claim was found lost, as a System.nanoTime value; set before `lossSignal` completes
              val lost = AtomicReference[Option[Long]](None)
              // the claim that replaced this one, when the loss named one; set before `lost`
              val lostTo     = AtomicReference[Option[FencingToken]](None)
              val lossSignal = new java.util.concurrent.CompletableFuture[Unit]()
              // the fence `body`'s write passes through
              val gate    = SyncCommitter(threadId)
              var outcome = Option.empty[Result[A]]
              // as a run's: a lost claim, or a renewal that could not be joined - which a store that
              // serialises its calls would queue the release behind - must not hold the change's end.
              // A claim the change consumed is not given back: the heartbeat is only halted
              Using.resource(new AutoCloseable {
                def close(): Unit =
                  if outcome.exists(consumesClaim) then heartbeat.foreach(_.halt())
                  else stopAndRelease(threadId, claim.token, heartbeat, lost.get)
              }) { _ =>
                // the loss, found by the heartbeat or by a write the store refused as stale, with the claim
                // that replaced this one if it named one; idempotent, keeping the first replacement named
                def claimLost(current: Option[FencingToken]): Unit =
                  current.foreach(c => lostTo.compareAndSet(None, Some(c)): Unit)
                  lost.compareAndSet(None, Some(System.nanoTime())): Unit
                  // fenced before the caller is woken, so no write begins once the loss is acted on
                  if gate.fence(GraphError.StaleClaim(threadId.value, claim.token, lostTo.get)) then
                    GraphRuntime.logger.warn(
                      s"Change ${named(config.runId)} on thread '${threadId.value}' lost its claim with its write in the store; it may still land"
                    )
                  lossSignal.complete(()): Unit
                heartbeat = Some(
                  Heartbeat(
                    threadId,
                    config.runId,
                    claim.token,
                    sentAt,
                    "its change is refused if not yet committed",
                    current => claimLost(current)
                  )
                )
                val result =
                  boundedByLoss(s"llm4s-change-${threadId.value}", lost, lossSignal, () => changeReturned(threadId)) {
                    () =>
                      // the write, if in flight, is given up on: counted before the thread is released
                      gate.orphan()
                      Left(GraphError.ThreadBusy(threadId.value, None, afterLoss(lostTo.get)))
                  }(() =>
                    body(
                      Held(threadId, claim, gate, reservation, current => claimLost(current), () => lost.get.isDefined)
                    )
                  )
                outcome = Some(result)
                result
              }
            }
        }

  /**
   * A change's hold on its thread ([[holding]]): its store claim, and the fence its one write passes.
   */
  final private class Held(
    val threadId: ThreadId,
    val claim: RunClaim,
    gate: Committer,
    reservation: (Option[String], RunId),
    lost: Option[FencingToken] => Unit,
    isLost: () => Boolean
  ):

    /**
     * Whether the change still holds its claim, checked in the store: not found lost here, and its token still the
     * thread's current claim, which a renewal proves ([[Checkpointer.renew]]). A renewal the store refuses as stale is
     * a loss found then, acted on as the heartbeat's is. Fences work done under this claim on other threads - a
     * cascade's deletes of the thread's descendants - which the claim's own fence on its one write cannot cover.
     */
    def holds: Result[Unit] =
      if isLost() then Left(GraphError.StaleClaim(threadId.value, claim.token, None))
      else
        Try(checkpointer.renew(threadId, claim.token, claims.ttl)).toResult.flatten match
          case Left(stale: GraphError.StaleClaim) =>
            lost(stale.current)
            Left(stale)
          case other => other.map(_ => ())

    /**
     * A loss of this claim that the store found when it refused a write fenced by it on another thread - a cascade's
     * delete of a descendant ([[deleteHeld]]) - acted on as a refused renewal is.
     */
    def foundLost(current: Option[FencingToken]): Unit = lost(current)

    /**
     * Makes `write`, the change's write to the store, unless the claim has been found lost: then it is
     * never begun, and is [[GraphError.StaleClaim]], as a write the store refused for a lost claim would
     * be. `commit` names the write for the `committing` test hook. A write the store refuses as stale is a
     * loss found then: it is acted on as the heartbeat's is (`lost`), before the change goes on, so the
     * change's end is bounded by it and its claim given back without waiting on the store.
     */
    def committing[B](commit: Commit)(write: => Result[B]): Result[B] =
      val outcome = gate.begun(commit)(reason => Left(reason))(write)
      outcome match
        case Left(stale: GraphError.StaleClaim) => lost(stale.current)
        case _                                  => ()
      outcome

    /**
     * Runs `f` holding `activeLock` if this change still holds its thread in this runtime - its caller has
     * not been given up on, or has, but has not yet let the thread go - so no run of this runtime holds it
     * meanwhile; whether it ran. `f` must not call the store.
     */
    def stillHeld(f: => Unit): Boolean =
      withLock(activeLock) {
        val held = active.get(threadId.value).exists(_ eq reservation)
        if held then f
        held
      }

  /**
   * Asks the store for a claim on `threadId` for `config.runId` and `config.tenantId`. A live claim held
   * by another run is [[GraphError.ThreadBusy]]. The store names its holder only when the claim is of
   * the caller's tenant ([[Checkpointer.claim]]), so the holder is shown as the store gave it, with or
   * without a checkpoint. The thread is then re-read and its tenant checked, for the checkpoint the
   * refusal names - the refusal's own is unchecked - so a caller from another tenant gets
   * `TenantMismatch` instead and learns nothing. A re-read that fails or throws is `ThreadBusy` naming
   * neither checkpoint nor holder: nothing unchecked is ever named. Any other failure of the store,
   * returned or thrown (non-fatally), is [[GraphError.CheckpointWriteFailed]].
   */
  private def claimThread(threadId: ThreadId, config: RunConfig): Result[RunClaim] =
    Try(checkpointer.claim(threadId, ClaimRequest(config.runId, config.tenantId, claims.ttl))).toResult.flatten match
      case Right(claim) =>
        val granted = s"Run ${named(config.runId)} claimed thread '${threadId.value}' with token ${claim.token.value}"
        // an operator who labels a runtime wants to find its claims; without a label it is noise
        if claims.label.isDefined then GraphRuntime.logger.info(granted) else GraphRuntime.logger.debug(granted)
        // any claim this runtime failed to give back has lapsed and been replaced: every one older than
        // this grant, which is all of them, as the store issues each token above every earlier one
        unreleased.updateWith(threadId.value)(_.filter(_.value > claim.token.value)): Unit
        Right(claim)
      // the refusal's own checkpoint is unchecked - it may be another tenant's, and embeds its run's id -
      // so a re-read that fails or throws falls back to a ThreadBusy naming nothing
      case Left(GraphError.ThreadBusy(_, _, holder)) =>
        // the store named the holder only for a claim of the caller's tenant (#1926), which a thread with
        // no checkpoint yet cannot otherwise show: another tenant's run may hold it before its first commit
        Try(busyAt(threadId, config, holder)).toResult.flatten.left.map {
          case refusal @ (_: GraphError.TenantMismatch | _: GraphError.ThreadBusy) => refusal
          case _ => GraphError.ThreadBusy(threadId.value, None, GraphError.BusyHolder.Unnamed)
        }
      case Left(other) => Left(GraphError.CheckpointWriteFailed(threadId.value, other))

  /** A run id as the runtime's logs name it: with the runtime's label ([[ClaimPolicy.label]]), if it has one. */
  private def named(runId: RunId): String = s"'${runId.value}'" + claims.label.fold("")(l => s" (runtime '$l')")

  /**
   * Gives back the claim whose token is `token`, logging (never throwing) a store that fails: its
   * claim then lapses after `claims.ttl`, and until then the thread is `ThreadBusy` to every runtime -
   * but for this runtime's [[deleteThread]], which tries once more to give it back first. It runs with
   * the interrupt flag clear, and sets it again afterwards: a cancelled or expired run still has its
   * flag set as it ends, and a store whose I/O honours interrupts would refuse the release, leaving the
   * thread busy for `ttl`.
   */
  private def releaseClaim(threadId: ThreadId, token: FencingToken): Unit =
    val interrupted = Thread.interrupted()
    releasing(threadId, token)
    if interrupted then Thread.currentThread().interrupt()

  /**
   * Runs `work` on a virtual thread named `name` and waits for it: for as long as it takes while the claim
   * holds, but once it is found lost - `lostAt`, a `System.nanoTime` value set before `lossSignal` is
   * completed - only until [[lossBound]] after the loss. Past that `work` is given up, and `givenUp`'s value
   * returned without it, so that a store hung on `work`'s next call cannot hold the caller, or the thread,
   * past the bound. `givenUp` runs on the caller's thread, before it is returned, and must not call the
   * store. `returned` runs on `work`'s thread once `work` has returned or thrown, given up or not.
   *
   * One `complete` decides between `work` and the give-up, so the caller gets exactly one outcome. An
   * interrupt flag set on the caller when it calls, or an interrupt while it waits, is passed on to
   * `work`'s thread - a store that honours interrupts then fails, as it would on the caller's thread - and
   * one left set on `work`'s thread is set on the caller's too. What `work` threw is thrown again.
   */
  private def boundedByLoss[A](
    name: String,
    lostAt: AtomicReference[Option[Long]],
    lossSignal: java.util.concurrent.CompletableFuture[Unit],
    returned: () => Unit
  )(givenUp: () => A)(work: () => A): A =
    val interruptedBefore = Thread.interrupted()
    // completed once: by `work`'s thread with its outcome, or by the caller giving it up
    val attempt = new java.util.concurrent.CompletableFuture[(Either[Throwable, A], Boolean)]()
    val worker = Thread.ofVirtual().name(name).start { () =>
      // as if `work` ran on the caller's thread
      if interruptedBefore then Thread.currentThread().interrupt()
      var outcome: Either[Throwable, A] = Left(new IllegalStateException(s"$name ended abnormally"))
      // completed on every exit
      Using.resource(new AutoCloseable {
        def close(): Unit =
          attempt.complete(outcome -> Thread.currentThread().isInterrupted): Unit
          DefaultRunHandle.guarded(returned()): Unit
      })(_ => outcome = DefaultRunHandle.guarded(work()))
    }
    @tailrec def waiting(interrupted: Boolean): Boolean =
      if attempt.isDone then interrupted
      else
        val waited = lostAt.get match
          case None =>
            DefaultRunHandle.guarded(java.util.concurrent.CompletableFuture.anyOf(attempt, lossSignal).get())
          case Some(at) =>
            val remaining = at + lossBound - System.nanoTime()
            if remaining <= 0 then
              attempt.complete(Right(givenUp()) -> false): Unit
              Right(())
            else DefaultRunHandle.guarded(attempt.get(remaining, TimeUnit.NANOSECONDS))
        waited match
          case Left(_: InterruptedException) =>
            worker.interrupt()
            waiting(interrupted = true)
          case _ => waiting(interrupted)
    val interrupted        = waiting(interruptedBefore)
    val (outcome, flagged) = attempt.join()
    if interrupted || flagged then Thread.currentThread().interrupt()
    outcome.fold(thrown => throw thrown, identity)

  /**
   * How long after a claim is found lost the holder's end is bounded: twice `claims.ttl`, in nanoseconds.
   * A run's own path to its end - an Async run's wait for its writer, at most `claims.ttl` - normally
   * finishes well within it; past it, the run is ended without its run thread, and its admission without
   * its claim's commit.
   */
  private def lossBound: Long = claims.ttl.toNanos * 2

  /**
   * How long a run's closing wait - an `Async` run's drain, or an `OnExit` run's exit commit - may go with a commit in
   * the store and none returning, while the run holds its claim, before the run is ended without it
   * ([[GraphError.CommitTimedOut]], #1919): twice `claims.ttl`, the bound a lost run's end already has. A store must
   * return from every call within a bound of its own ([[Checkpointer]]), and one that keeps claims alive does so well
   * inside `ttl`: a renewal must land within `ttl - renewEvery` of being due, and a store that serialises its calls
   * queues renewals behind its commits. So a healthy store never reaches this; it is a backstop for one that breaks
   * the contract, and needs no setting of its own.
   */
  private def closeTimeout: FiniteDuration = claims.ttl * 2

  /** [[closeTimeout]] in nanoseconds. */
  private def closeBound: Long = closeTimeout.toNanos

  /**
   * Stops `heartbeat` and gives back the claim whose token is `token`.
   *
   * A claim found lost - `lostAt` is when, as a `System.nanoTime` value, read before and again after the
   * heartbeat stops - is given back on a detached thread ([[releaseDetached]]) without joining the
   * heartbeat, whose renewal may be stuck in the store: waited for only until [[lossBound]] after the
   * loss, so that a healthy store still frees the thread at once and a hung one never holds the end past
   * that bound. A claim not lost is given back once the heartbeat's beat and any renewal in flight are
   * joined - at most `claims.ttl` - so that no renewal lands after the release: synchronously, as
   * [[releaseClaim]], when they were, so a caller that sees the end of the change or run can claim the
   * thread at once; otherwise on a detached thread, not waited for, so the end does not serialise the
   * release behind the renewal it could not join. With `detach` - a run whose closing wait was given up on while
   * it held its claim ([[Run.closedWithoutRunThread]]) - the heartbeat is halted, not joined, and the claim given
   * back on a detached thread that is not waited for, as for a loss past its bound: the store is stuck on a commit,
   * and may serialise the release behind it. Never throws, and keeps the caller's interrupt flag.
   */
  private def stopAndRelease(
    threadId: ThreadId,
    token: FencingToken,
    heartbeat: Option[Heartbeat],
    lostAt: => Option[Long],
    detach: Boolean = false
  ): Unit =
    def untilBound(at: Long): Long = math.max(0L, at + lossBound - System.nanoTime())
    lostAt match
      case Some(at) =>
        heartbeat.foreach(_.halt())
        releaseDetached(threadId, token, untilBound(at))
      // a run whose closing commit is stuck in the store: neither its renewal nor its release is waited for
      case None if detach =>
        heartbeat.foreach(_.halt())
        releaseDetached(threadId, token, 0L)
      case None =>
        val joined = heartbeat.forall(_.stop())
        lostAt match
          case Some(at)       => releaseDetached(threadId, token, if joined then untilBound(at) else 0L)
          case None if joined => releaseClaim(threadId, token)
          case None           => releaseDetached(threadId, token, 0L)

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
   * Forgets the hub's hand-over mark for `threadId` unless the thread is still in use in this runtime:
   * held by a run, or with a commit its run gave up on still in flight ([[orphans]]). Checked under
   * `activeLock`, so it never races an admission, nor falls between the two steps of [[release]], which
   * takes the same lock. The caller holds `activeLock`.
   */
  private def forgetIfIdle(threadId: ThreadId): Unit =
    if !active.contains(threadId.value) && !orphans.contains(threadId.value) then hub.release(threadId)

  /** Counts `count` orphaned commits in flight on `threadId` ([[orphans]]). */
  private def orphanInFlight(threadId: ThreadId, count: Int): Unit =
    withLock(activeLock)(orphans.updateWith(threadId.value)(n => Some(n.getOrElse(0) + count))): Unit

  /**
   * An orphaned commit on `threadId` has returned from the store - and, if it landed, been handed over:
   * the hub's mark is forgotten if nothing else uses the thread ([[forgetIfIdle]]).
   */
  private def orphanReturned(threadId: ThreadId): Unit =
    withLock(activeLock) {
      orphans.updateWith(threadId.value)(_.map(_ - 1).filter(_ > 0))
      forgetIfIdle(threadId)
    }

  /**
   * Records that the claim whose token is `token` was not given back ([[unreleased]]), unless a newer
   * one on the thread already is: a detached release ([[releaseDetached]]) may fail after a later run's
   * release on the same thread has failed too, and the newer token is the one that may still be live.
   * Atomic: `updateWith` on a concurrent `TrieMap` retries until its compare-and-set lands.
   */
  private def recordUnreleased(threadId: ThreadId, token: FencingToken): Unit =
    unreleased.updateWith(threadId.value)(recorded =>
      Some(recorded.filter(_.value > token.value).getOrElse(token))
    ): Unit

  private def releasing(threadId: ThreadId, token: FencingToken): Unit =
    DefaultRunHandle.guarded(checkpointer.release(threadId, token)) match
      // only this release's own entry: a newer token recorded since is another claim, still to give back
      case Right(Right(_)) => unreleased.remove(threadId.value, token): Unit
      case Right(Left(error)) =>
        recordUnreleased(threadId, token)
        GraphRuntime.logger.warn(
          s"Releasing the claim on thread '${threadId.value}' failed; it lapses after ${claims.ttl}: ${error.message}"
        )
      case Left(thrown) =>
        if thrown.isInstanceOf[InterruptedException] then Thread.currentThread().interrupt()
        recordUnreleased(threadId, token)
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
    admit: Admitting => Result[Run[I, O]]
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
            val signal = StopSignal()
            // what the run's own subscriptions must not be given: a write it abandoned that lands late
            val abandoned = AbandonedWrites(config.runId)
            // the run's own subscriptions: its thread is its tenant's once the claim commits, and
            // every event naming another tenant is still refused
            val runScope = TenantScope.Of(config.tenantId.map(_.value), confirmed = true)
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
              observation =
                observer.map(o => hub.observe(threadId, o.capacity, o.listener, abandoned.apply, runScope, o.nested))
              // the observer takes its place in the log at the first commit fenced by the run's claim
              admit(Admitting(signal, token => observation.foreach(_.claimed(token)))).map { run =>
                admitted = Some(run)
                val subscription = observation.map(_.start(run.claimSeq - 1))
                val handle = DefaultRunHandle[O](
                  threadId,
                  run.runId,
                  run.claimSeq,
                  signal,
                  (afterSeq, capacity, nested, listener) =>
                    dispatched(threadId, afterSeq, capacity, listener, abandoned.apply, runScope, nested),
                  subscription
                )
                // the store claim first - its heartbeat stopped and joined before it - then this
                // runtime's hold: once a caller sees the result, any runtime may claim the thread
                def releaseBoth(): Unit =
                  run.releaseClaim()
                  release(threadId)
                // from now on a lost claim stops the run as a cancel does; before, it was only recorded.
                // Whatever the run is doing - finishing included, or stuck in a store call that ignores
                // interrupts - its end is then bounded: if the run thread has not begun to settle it by
                // twice `claims.ttl` after the loss, it is settled without it, as ClaimLost
                run.stopper = () =>
                  handle.stop(StopCause.ClaimLost)
                  handle.endWithin(run.lossDeadline, () => run.endedWithoutRunThread(), () => releaseBoth())
                // while the claim holds, a closing wait stuck on the store is bounded too (#1919), through the
                // same settling as the loss watchdog: whichever comes first ends the run
                run.closing = () =>
                  handle.closeWithin(
                    run.closed,
                    () => run.stalledSince,
                    closeBound,
                    () => run.closedWithoutRunThread(),
                    () => releaseBoth()
                  )
                run.abandonedWrites = Some(abandoned)
                handle.launch(() => run.execute(), run.crashed, () => releaseBoth(), run.deadline)
                launched = true
                // a loss found before the stopper was set only recorded its cause: its end is bounded too
                if run.claimWasLost then run.stopper()
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
   * the thread, and set a new mark, until it is released - unless a commit the run gave up on is still
   * in flight ([[orphans]]). Then the mark is kept until every such commit returns: it is the top of a
   * hand-over that has finished, and an orphan, not yet handed over, is above it, so a subscription
   * made meanwhile is seeded with it and judges what it replays without waiting for an orphan - which
   * holds, or waits for, the thread's commit lock, and may hang in the store. Both steps happen under `activeLock`, as
   * [[orphanReturned]]'s check does, so an orphan that returns either sees the thread still held - and
   * the mark is then forgotten here, after its hand-over - or sees it free and forgets the mark itself.
   */
  private def release(threadId: ThreadId): Unit =
    withLock(activeLock) {
      // a run's children are over once it releases the thread, even while an orphan keeps the mark (#2074)
      hub.forgetChildren(threadId)
      if !orphans.contains(threadId.value) then hub.release(threadId)
      releasingLocally(threadId)
      active.remove(threadId.value)
    }: Unit

  /** A test hook run by [[release]] between forgetting the hand-over mark and freeing the local slot. */
  @volatile private[graph] var releasingLocally: ThreadId => Unit = _ => ()

  /** The runtime's store; for tests. */
  private[graph] def store: Checkpointer = checkpointer

  /** Whether a thread is waiting for `activeLock`; for tests. */
  private[graph] def activeLockContended: Boolean = activeLock.hasQueuedThreads

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
    else busyAt(threadId, config, GraphError.BusyHolder.Run(holderRun))

  /**
   * [[busy]]'s store half: the thread's latest checkpoint, tenant-checked, and `holder`, which the caller
   * has already established is of the caller's tenant if it names a run: a run of this runtime, or a claim
   * the store named because it was of the caller's tenant ([[Checkpointer.claim]]).
   */
  private def busyAt(threadId: ThreadId, config: RunConfig, holder: GraphError.BusyHolder): Result[Nothing] =
    checkpointer.latest(threadId).flatMap {
      case None =>
        Left(GraphError.ThreadBusy(threadId.value, None, holder))
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
    admitting: Admitting,
    origin: Option[RunOrigin],
    notAfter: Option[Instant] = None
  ): Run[I, O] =
    new Run(graph, threadId, config, committer(threadId, durability), firstSuperstep, admitting, origin, notAfter)

  /**
   * What admission gives the run it admits: the run's stop signal, and `claimed`, told the token the
   * run's claim is granted with before the claim's commit begins - so the run's observer
   * ([[EventHub.observe]]) knows which commit is its place in the log.
   */
  final private class Admitting(val signal: StopSignal, val claimed: FencingToken => Unit)

  private def reusableWrites(
    graph: CompiledGraph[?, ?],
    execution: Execution,
    stored: StoredCheckpoint
  ): Result[Map[TaskId, ReusedWrite]] =
    val frontier = execution.frontier.map(_.id).toSet
    stored.pendingWrites.foldLeft[Result[Map[TaskId, ReusedWrite]]](Right(Map.empty)) { (acc, write) =>
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
          // the digest the write was recorded with, or one of the write as stored: never of the decoded result
          yield reused.updated(
            task.id,
            ReusedWrite(result, write.resultDigest.getOrElse(CompiledGraph.writeDigest(write)))
          )
      }
    }

  private def committer(threadId: ThreadId, durability: Durability): Committer =
    durability match
      case Durability.Sync   => SyncCommitter(threadId)
      case Durability.Async  => AsyncCommitter(threadId)
      case Durability.OnExit => OnExitCommitter(threadId)

  /**
   * Commits and delivers under the thread's commit lock, so delivery order is commit order on the
   * thread; returns the committed
   * events. A store that throws (non-fatally) rather than returning `Left` fails the commit the
   * same way; an `InterruptedException` is not a store failure and propagates.
   */
  private def commitAndDeliver(threadId: ThreadId, commit: Commit): Result[Vector[EventRecord]] =
    commitLocks.withThreadLock(threadId) {
      Try(checkpointer.commit(threadId, commit)).toResult.flatten.map { records =>
        hub.durable(threadId, records)
        records
      }
    }

  abstract private class Committer(threadId: ThreadId):
    def submit(commit: Commit): Unit
    def failure: Option[LLMError]

    /**
     * Guards `fencedBy` and `inFlight` together, so that a commit's start and a fence are one atomic
     * step each: a commit either begins before the fence, and the fence counts it as in flight, or finds
     * the fence and never begins. Held only to read or change them, never across a store call.
     */
    private val gate = new java.util.concurrent.locks.ReentrantLock()

    /** Set by [[fence]]: what a commit not yet begun fails with, instead of being written; under `gate`. */
    private var fencedBy: Option[LLMError] = None

    /**
     * How many commits have begun and not returned; under `gate`. A count, not a flag: a Sync run's
     * concurrent tasks each submit their own commit, so several can be begun at once - one in the store,
     * the others waiting for the thread's commit lock - and the first to return must not hide the rest.
     */
    private var inFlight = 0

    /**
     * How many of the commits in flight are counted as orphans ([[orphan]]); under `gate`. Never more
     * than `inFlight`: each orphan is a commit in flight when the committer was orphaned, and whichever
     * commit returns first settles one of them - the thread's count ([[orphans]]) needs only how many.
     */
    private var orphanCounted = 0

    /**
     * When a commit last began or returned, as a `System.nanoTime` value; under `gate`. With one in flight, how long
     * the committer has made no progress: what bounds a run's closing wait ([[stalledSince]]).
     */
    private var progressAt = System.nanoTime()

    /**
     * When the committer last made progress - a commit began or returned - if a commit is in flight now; `None`
     * with none in flight. Never waits on the store.
     */
    def stalledSince: Option[Long] = withLock(gate)(Option.when(inFlight > 0)(progressAt))

    /**
     * Whether [[close]] waits on the store - for an `Async` queue, or `OnExit`'s exit commit - and so is bounded
     * while the run holds its claim ([[Run.closeCommitter]]). A `Sync` run's commits are made as it goes.
     */
    def drains: Boolean = true

    /**
     * From now on, every commit not yet begun is skipped, and recorded as failing with `reason`; one
     * already begun is not touched, and may land. For a run whose claim is found lost at any stage, even
     * while it commits its outcome: nothing it has not begun to write may land. A commit begins - or is
     * skipped - atomically with this ([[begun]]), so none starts once the fence is up. Never waits
     * on the store, so the heartbeat can call it whatever the run thread is doing. Whether a commit had
     * begun and not returned when the fence went up.
     */
    def fence(reason: LLMError): Boolean =
      val (fencedNow, begun) = withLock(gate) {
        val first = fencedBy.isEmpty
        if first then fencedBy = Some(reason)
        (first, inFlight > 0)
      }
      if fencedNow then fenced(threadId, begun)
      begun

    /**
     * Begins `write` unless the committer is fenced, as one atomic step with the check; `skipped` with the
     * fence's reason otherwise. The commit counts as in flight until `write` returns or throws.
     */
    def begun[A](commit: Commit)(skipped: LLMError => A)(write: => A): A =
      val fence = withLock(gate) {
        if fencedBy.isEmpty then
          inFlight += 1
          progressAt = System.nanoTime()
        fencedBy
      }
      fence match
        case Some(reason) => skipped(reason)
        case None =>
          Using.resource(new AutoCloseable {
            def close(): Unit =
              val orphaned = withLock(gate) {
                inFlight -= 1
                progressAt = System.nanoTime()
                val counted = orphanCounted > 0
                if counted then orphanCounted -= 1
                counted
              }
              if orphaned then orphanReturned(threadId)
          }) { _ =>
            GraphRuntime.this.committing(threadId, commit)
            write
          }

    /**
     * Marks the run as ended without waiting for this committer, once it is fenced, so no commit begins
     * after this. Every commit in flight now - several, for a Sync run's concurrent tasks - is counted
     * as orphaned on its thread ([[orphans]]) until it returns: meanwhile the hub keeps its hand-over
     * mark for the thread ([[release]]), and once the last returns the mark is forgotten if no run holds
     * the thread ([[orphanReturned]]). Called before the run lets its thread go.
     */
    def orphan(): Unit =
      withLock(gate) {
        if inFlight > orphanCounted then
          orphanInFlight(threadId, inFlight - orphanCounted)
          orphanCounted = inFlight
      }

    /**
     * Lets the committer go when its run is settled without its run thread ([[Run.endedWithoutRunThread]]):
     * fences it with `reason`, counts every commit in flight as an orphan ([[orphan]]), and frees whatever
     * the committer owns - an Async writer is shut down - without waiting for anything, the store above
     * all. The run thread may never return, so nothing it would have closed may be left to it. Idempotent,
     * and safe against the run thread's later [[abandon]] or [[close]], and against a commit in flight,
     * which still lands, or not, as an orphan.
     */
    def settledWithoutRunThread(reason: LLMError): Unit =
      fence(reason): Unit
      orphan()

    /**
     * Told when the store refuses a submitted commit as stale - proof that another run took the thread
     * over - so the run stops at once rather than at its next superstep, and its end is bounded by the loss.
     * Set by the run; every mode calls it from the thread that made the commit - OnExit from its `close`,
     * before the run releases its claim.
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
     * in flight, as `close` does, but - for Async - at most `claims.ttl`. Never throws. Whether a
     * commit is still in flight when it returns, and so may land after the run has ended.
     */
    def abandon(reason: LLMError): Boolean

  final private class SyncCommitter(threadId: ThreadId) extends Committer(threadId):
    private val failed = AtomicReference[Option[LLMError]](None)
    def submit(commit: Commit): Unit =
      if failed.get.isEmpty then
        begun(commit)(reason => failed.compareAndSet(None, Some(reason)): Unit) {
          commitAndDeliver(threadId, commit).left.foreach(refused(failed, _))
        }
    def failure: Option[LLMError]                 = failed.get
    def close(): Unit                             = ()
    override def drains: Boolean                  = false
    override def forgetInterruptedFailure(): Unit = failed.set(None)
    def abandon(reason: LLMError): Boolean =
      failed.set(Some(reason))
      false

  final private class AsyncCommitter(threadId: ThreadId) extends Committer(threadId):
    private val failed = AtomicReference[Option[LLMError]](None)
    private val writer: ExecutorService = Executors.newSingleThreadExecutor { runnable =>
      val thread = new Thread(runnable, s"llm4s-graph-writer-${threadId.value}")
      thread.setDaemon(true)
      writerCreated(threadId, thread)
      thread
    }
    def submit(commit: Commit): Unit =
      // the writer refuses work only once it is shut down, and a run settled without its run thread shuts
      // it down with the failure already recorded ([[settledWithoutRunThread]]): a run thread that comes
      // back and submits finds the commit dropped, as a queued one would be
      catching(classOf[RejectedExecutionException]).opt {
        writer.execute { () =>
          // a failed commit stops the queue: a later commit must not land on top of a lost one
          if failed.get.isEmpty then
            begun(commit)(reason => failed.compareAndSet(None, Some(reason)): Unit) {
              commitAndDeliver(threadId, commit).left.foreach(refused(failed, _))
            }
        }
      }: Unit
    def failure: Option[LLMError] = failed.get

    /**
     * Also shuts the writer down, without waiting for it: commits queued find the failure and skip
     * themselves, and the writer - a platform thread - ends once the one in flight, if any, returns. The
     * run thread, stuck in a node that ignores interrupts, say, may never reach [[abandon]], so the
     * writer must not wait for it. `shutdown`, not `shutdownNow`: a commit in the store is fenced by the
     * run's token already, and is left to land or fail as an orphan, as [[abandon]] leaves it -
     * interrupting it would only fail it part-way through its hand-over to the thread's subscribers.
     */
    override def settledWithoutRunThread(reason: LLMError): Unit =
      failed.set(Some(reason))
      super.settledWithoutRunThread(reason)
      writer.shutdown()

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
     * its thread's commit lock like any other commit's - but the run's own listeners never receive them
     * ([[AbandonedWrites]]). Never throws; an interrupt is remembered and set again. Whether the writer
     * was still busy when the wait ended.
     */
    def abandon(reason: LLMError): Boolean =
      failed.set(Some(reason))
      orphan()
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
      !writer.isTerminated

  final private class OnExitCommitter(threadId: ThreadId) extends Committer(threadId):
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

    /**
     * Makes the one exit commit. One the store refuses as stale is reported as Sync's and Async's are
     * ([[onStale]]) - once the lock is let go, and before the run goes on to release its claim - so the
     * loss is recorded and the run's end bounded by it, rather than its release waiting on the store.
     */
    def close(): Unit =
      val refusal = withLock(lock) {
        val last = checkpoint.map(_.withParent(durableParent.flatten))
        token.foreach { fence =>
          if last.isDefined || writes.nonEmpty || events.nonEmpty then
            val exit = Commit(fence, last, writes, events, retract)
            begun(exit)(reason => failed = Some(reason)) {
              failed = commitAndDeliver(threadId, exit).left.toOption
            }
        }
        failed
      }
      refusal match
        case Some(stale: GraphError.StaleClaim) => onStale(stale)
        case _                                  => ()
    def abandon(reason: LLMError): Boolean = withLock(lock) {
      checkpoint = None
      writes = Vector.empty
      events = Vector.empty
      retract = false
      failed = Some(reason)
      false
    }

  /**
   * Renews a claim from its grant until stopped, and judges the claim's lease itself, so that a run
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
   * after the heartbeat is stopped.
   *
   * The lease's end and whether the heartbeat is over change only together, under `lock`: a landed
   * renewal publishes its lease only while the heartbeat is not over, and the beat declares the lease
   * passed only against the lease published at that moment, reading the clock before it takes the lock.
   * So a renewal published before the beat's decision always counts, and one published after it - or
   * after a loss found otherwise, or `stop` - is ignored. The beat never reads the lease's end
   * outside the lock to decide, and nothing slow (the store, the clock, `lost`) runs under it. A
   * renewal is started only by such a decision, on a reading taken after the beat's wait, and is sent
   * at that reading: a beat that wakes late, past the lease's end, declares the loss and sends none.
   *
   * [[stop]] stops the beat and waits for it and for a renewal in flight - no longer than `claims.ttl`
   * in all, so a store that hangs cannot hang the end of a run - so that no renewal lands after the
   * claim is released, and says whether both were joined. A renewal still blocked then is left to
   * finish on its own: its outcome is ignored, and it can extend the claim only if the release that
   * follows fails. Its holder must then release without waiting on the store, which may serialise the
   * release behind that renewal ([[stopAndRelease]]). Stopping is idempotent, never throws, and keeps
   * the stopping thread's interrupt flag.
   */
  final private class Heartbeat(
    threadId: ThreadId,
    holder: RunId,
    token: FencingToken,
    grantSentAt: Long,
    consequence: String,
    lost: Option[FencingToken] => Unit
  ):
    private val stopped = new CountDownLatch(1)

    /** Guards `over` and `leaseEnd` together; held only to read or change them. */
    private val lock = new java.util.concurrent.locks.ReentrantLock()

    /** Set once the claim is found lost or the heartbeat stops; written under `lock`. */
    @volatile private var over = false

    /** When the lease ends, as a [[ticker]] value; written under `lock`. */
    private var leaseEnd = grantSentAt + claims.ttl.toNanos

    /** The renewal in flight, if any: started only by the beat, and read by `stop` once the beat ended. */
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
        s"Run ${named(holder)} lost its claim on thread '${threadId.value}': $why; $consequence"
      )
      DefaultRunHandle.guarded(lost(current)): Unit

    /** How long the lease has left at `now`, or `None` once the heartbeat is over. */
    private def remainingAt(now: Long): Option[Long] = withLock(lock)(Option.when(!over)(leaseEnd - now))

    private def passed(): Unit = lose(None, s"its lease of ${claims.ttl} passed with no renewal landing")

    /**
     * At `now`, under `lock` with any renewal's publication: ends the heartbeat if the lease has passed
     * (`true`), and otherwise starts a renewal sent at `now`, unless one is in flight or the heartbeat is
     * over - so that no renewal starts once the lease has passed, or once `stop` or `halt` ended it.
     */
    private def renewOrEnd(now: Long): Boolean =
      withLock(lock) {
        if over then false
        else if now - leaseEnd >= 0 then
          over = true
          true
        else
          if renewal.get.forall(!_.isAlive) then renewal.set(Some(renewing(now)))
          false
      }

    /**
     * Each beat reads the clock twice, outside `lock`, and decides on each reading against the lease
     * published when it decides: once to judge the lease and size its wait, and again once the wait is
     * over, before it starts a renewal - so that a beat delayed after either reading, or in the wait
     * itself, past the lease's end, sends no renewal that could revive a lapsed claim the store has not
     * yet handed over, and declares the loss instead.
     */
    @tailrec private def beating(): Unit =
      val now = ticker()
      if end(ends => now - ends >= 0) then passed()
      else
        remainingAt(now) match
          case None => ()
          case Some(remaining) =>
            DefaultRunHandle.guarded(
              stopped.await(math.max(1L, math.min(claims.renewEvery.toNanos, remaining)), TimeUnit.NANOSECONDS)
            ) match
              case Right(false) =>
                if renewOrEnd(ticker()) then passed() else beating()
              // stopped; nothing else wakes or interrupts it
              case _ => ()

    /** A renewal whose request is sent at `sentAt`, the [[ticker]] reading the beat started it on. */
    private def renewing(sentAt: Long): Thread =
      Thread.ofVirtual().name(s"llm4s-claim-renew-${threadId.value}").start { () =>
        DefaultRunHandle.guarded(checkpointer.renew(threadId, token, claims.ttl)) match
          case Right(Right(_)) =>
            // the lease runs from when the request was sent, never from when its answer arrived
            val renewed = sentAt + claims.ttl.toNanos
            withLock(lock)(if !over && renewed - leaseEnd > 0 then leaseEnd = renewed)
          case Right(Left(stale: GraphError.StaleClaim)) =>
            if end(_ => true) then lose(stale.current, stale.message)
          case Right(Left(error)) =>
            if !over then
              GraphRuntime.logger.warn(
                s"Renewing the claim of run ${named(holder)} on thread '${threadId.value}' failed: ${error.message}"
              )
          case Left(thrown) =>
            if !over then
              GraphRuntime.logger.warn(
                s"Renewing the claim of run ${named(holder)} on thread '${threadId.value}' failed",
                thrown
              )
        renewalSettled(threadId)
      }

    /**
     * Ends the heartbeat without waiting for its beat or a renewal in flight: for a claim found lost,
     * whose renewal may be stuck in the store and whose outcome no longer matters.
     */
    def halt(): Unit =
      withLock(lock) { over = true }
      stopped.countDown()

    /** Ends the heartbeat; whether the beat and any renewal in flight were joined in time. */
    def stop(): Boolean =
      withLock(lock) { over = true }
      stopped.countDown()
      val deadline = System.nanoTime() + claims.ttl.toNanos
      // the beat first, so that no renewal starts after the one read below
      val interrupted = joinWithin(beat, deadline, Thread.interrupted())
      if renewal.get.exists(_.isAlive) then joiningRenewal(threadId)
      val all = renewal.get.fold(interrupted)(joinWithin(_, deadline, interrupted))
      if all then Thread.currentThread().interrupt()
      !beat.isAlive && renewal.get.forall(!_.isAlive)

  /** One run: drives supersteps, recording task results, checkpoints and events as it goes. */
  final private class Run[I, O](
    graph: CompiledGraph[I, O],
    threadId: ThreadId,
    config: RunConfig,
    committer: Committer,
    firstSuperstep: Int,
    admitting: Admitting,
    /** The child thread's origin, written on every checkpoint of the run; `None` on a top-level thread. */
    origin: Option[RunOrigin],
    /**
     * An absolute cap on the run's deadline, by the runtime's clock: a child's parent's deadline (design 4.42). The
     * deadline fixed at the claim is the sooner of it and `budgets.timeout` from then, so admission's own latency never
     * moves a child's deadline past its parent's.
     */
    notAfter: Option[Instant] = None
  ):
    private val signal      = admitting.signal
    val runId               = config.runId
    private val cause       = signal.cause
    private var checkpoints = 0L

    /** The `n` of the checkpoint this run claims over; its own checkpoints count on from it. */
    private var parentOrdinal = 0L

    /**
     * Where the run is: the execution `loop` is at and its checkpoint; set by the claim. Read by
     * [[endedWithoutRunThread]] on another thread.
     */
    @volatile private var position: (Execution, String) = null

    /** What the claim leaves for `execute`: pending writes `loop` reuses. */
    private var claimedReused: Map[TaskId, ReusedWrite] = Map.empty

    /** The sequence number of the claim's event; set by the claim. */
    var claimSeq: Long = 0L

    /** When `budgets.timeout` expires, as a `System.nanoTime` value; fixed by the claim. */
    var deadline: Option[Long] = None

    /** The same deadline by the runtime's `clock`, for [[RunContext.deadline]]; fixed by the claim. */
    private var deadlineAt: Option[Instant] = None

    /** The thread's state the claim continues from, for [[RunContext.admitted]]; set by the claim. */
    private var admittedState: Option[ThreadState] = None

    /**
     * The earlier run's children that `start` admission's retention pass found busy (#2073): the only `Running` or
     * `Suspended` children the run's terminal pass may abandon ([[retainBeforeSettling]]). Set at admission, before
     * the run starts any child, so it never holds one of this run's.
     */
    @volatile private var abandonLater: Set[ThreadId] = Set.empty

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
     * launched is recorded, as [[StopCause.ClaimLost]], and the run stops before its first superstep; the
     * claim's commit is fenced against it, and given up once the loss is old enough ([[boundedByLoss]]). A
     * claim's commit the store refuses as stale is a loss found then: admission fails
     * [[GraphError.ClaimLost]], naming the current claim the store named unless the heartbeat recorded the
     * loss first, so the two agree on one claim. `fence` is another thread's claim the store checks with the claim's
     * commit ([[Commit.fence]]): a refusal for it wrote nothing and is [[GraphError.ClaimLost]] on that thread, not a
     * loss of this run's claim, which is given back as usual.
     */
    def admit(
      execution: Execution,
      parent: Option[String],
      event: RunClaim => RunEvent,
      carry: () => Result[(Vector[PendingWrite], Map[TaskId, ReusedWrite])] = () => Right(Vector.empty -> Map.empty),
      retainChildren: Boolean = false,
      amend: Execution => Result[Execution] = Right(_),
      fence: Option[(ThreadId, FencingToken)] = None,
      metadata: Option[ThreadMetadata] = None
    ): Result[Run[I, O]] =
      val sentAt = ticker()
      claimThread(threadId, config).flatMap { granted =>
        claim = Some(granted)
        admitting.claimed(granted.token)
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
            boundedByLoss(() =>
              // the tasks created for the run before its claim get the claim's root lineage (design §4.24)
              carry().flatMap((carried, reused) => amend(execution).map((carried, reused, _))).flatMap {
                (carried, reused, execution) =>
                  // a top-level thread is pruned under the store claim, before the run's first event: nothing of the
                  // run exists to be pruned. A child thread only after the claim commit, which stores what `amend`
                  // folded from the events retention would remove (design 4.42): a commit that fails prunes nothing
                  val child = origin.isDefined
                  if !child then config.retention.foreach(retain)
                  if retainChildren then
                    abandonLater = keepChildren(
                      threadId,
                      config,
                      parent,
                      granted.token,
                      parentFence(threadId, granted.token, () => lostAt.get.isDefined),
                      abandon = _ => true
                    )
                  committed(
                    execution.stampedBy(granted.token.value),
                    parent,
                    event(granted),
                    carried,
                    reused,
                    fence,
                    metadata
                  )
                    .map { run =>
                      if child then config.retention.foreach(retain)
                      run
                    }
              }
            )
          admitted = outcome.isRight
          outcome
        }
      }

    /**
     * Runs `claiming` - admission's read under the claim and the claim's commit - on a virtual thread,
     * `llm4s-admit-<threadId>`, bounded by the loss ([[GraphRuntime.boundedByLoss]]): once the claim is
     * found lost it is waited for only until [[lossDeadline]], and past that given up, the admission failing
     * [[GraphError.ClaimLost]] without it, so that a store hung on the claim's commit cannot keep `start`,
     * `recover` or `resume` from returning, or the thread held. A commit given up on is an orphan
     * ([[Committer.orphan]]): if it lands later, it is fenced by the claim's token, and the hub keeps its
     * mark for the thread only until it returns; the run is never launched.
     */
    private def boundedByLoss(claiming: () => Result[Run[I, O]]): Result[Run[I, O]] =
      GraphRuntime.this.boundedByLoss(s"llm4s-admit-${threadId.value}", lostAt, lossSignal, () => ()) { () =>
        // the claim's commit, if in flight, is given up on: counted before the thread is released
        committer.orphan()
        Left(GraphError.ClaimLost(threadId.value, token, lostTo.get))
      }(claiming)

    private def committed(
      execution: Execution,
      parent: Option[String],
      event: RunEvent,
      carried: Vector[PendingWrite],
      reused: Map[TaskId, ReusedWrite],
      fence: Option[(ThreadId, FencingToken)],
      metadata: Option[ThreadMetadata]
    ): Result[Run[I, O]] =
      for
        claimed <- newCheckpoint(execution, parent, CheckpointStatus.Running)
        claim = Commit(
          token,
          Some(claimed),
          carried.map(_.copy(checkpointId = claimed.id)),
          Vector(draft(Some(claimed.id), None, event))
        ).withFence(fence).withMetadata(metadata)
        // through the committer's fence, so that a claim found lost before it begins never lands
        // a refusal for the claim above is ClaimLost on that thread: nothing was written, and this run's claim is
        // given back as usual
        records <- committer.begun(claim)(reason => Left(reason))(
          commitAndDeliver(threadId, claim).left.map(fenceRefused(threadId, fence))
        ) match
          case Left(lost: GraphError.ClaimLost) => Left(lost)
          // the claim lapsed, or another run took it over, before its commit landed: the loss is recorded
          // as the heartbeat records one, so a loss it declared meanwhile and this refusal name one claim
          case Left(stale: GraphError.StaleClaim) =>
            claimLost(stale.current)
            Left(GraphError.ClaimLost(threadId.value, token, lostTo.get))
          // another run advanced the thread first: the thread is re-read, and its tenant checked, and
          // the ThreadBusy names the re-read's checkpoint. The conflict's own `latest` is unchecked - it
          // may be another tenant's, and embeds its run's id - so it is never named: a re-read that
          // finds no checkpoint names none, and one that fails or throws falls back to a ThreadBusy
          // naming nothing, rather than to a store error.
          case Left(_: GraphError.CheckpointConflict) =>
            Try(
              busyAt(threadId, config, GraphError.BusyHolder.Unnamed)
            ).toResult.flatten.left.map {
              case refusal @ (_: GraphError.TenantMismatch | _: GraphError.ThreadBusy) => refusal
              case _ => GraphError.ThreadBusy(threadId.value, None, GraphError.BusyHolder.Unnamed)
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
        val nowNanos = System.nanoTime()
        // read only for a run with a deadline, as before the cap existed
        lazy val nowAt = clock.instant()
        // the time left until the cap, from now, by the runtime's clock; never negative
        val capped = notAfter.map(at => java.time.Duration.between(nowAt, at).toNanos.max(0L))
        val left   = (config.budgets.timeout.map(_.toNanos) ++ capped).minOption
        deadline = left.map(nowNanos + _)
        deadlineAt = left.map(nowAt.plusNanos)
        admittedState = Some(execution.state)
        this

    /**
     * Prunes the thread to `policy` as the run is admitted, under the store claim, so no other run writes meanwhile; a
     * failure is logged, never the run's. `keepAll` removes nothing, so it is skipped. A top-level thread is pruned
     * before the run's claim commit, so nothing of this run exists yet to be pruned, by any limit. A child thread is
     * pruned only after it, before the run is launched (design 4.42): the claim checkpoint stores the usage the child
     * runner folded from the terminal events this removes, so a crash before it removes nothing unfolded; the claim's
     * own checkpoint and event count against the limits, and `maxEvents` is at least 1 ([[GraphRuntime.childConfig]]).
     */
    private def retain(policy: RetentionPolicy): Unit =
      if policy != RetentionPolicy.keepAll then
        Try(checkpointer.prune(threadId, policy)).toResult.flatten.left.foreach(error =>
          GraphRuntime.logger.warn(
            s"Pruning ${threadId.value} to its retention as run ${runId.value} was admitted failed: ${error.message}"
          )
        )

    /** The store claim this run holds, from [[admit]] on; `None` before it, or if it was refused. */
    @volatile private var claim: Option[RunClaim] = None
    private val claimReleased                     = new AtomicBoolean(false)

    /** Renews the claim, from its grant until [[releaseClaim]] stops it. */
    @volatile private var heartbeat: Option[Heartbeat] = None

    /** The thread's current claim, when the store named it as this run's claim was found lost. */
    private val lostTo = AtomicReference[Option[FencingToken]](None)

    // the model usage of the tasks that failed: they commit nothing, so it goes on the run's RunFailed (§4.22)
    private val failedUsage = AtomicReference(UsageSummary())

    /** The usage of the attempts that committed nothing, for the run's terminal event; `None` when there is none. */
    private def lostUsage: Option[UsageSummary] = Option.when(failedUsage.get.requestCount > 0)(failedUsage.get)

    // the agent model steps of the tasks that failed, for the terminal event with their usage (design 4.45)
    private val failedSteps = new java.util.concurrent.atomic.AtomicLong(0L)

    /** Keeps the usage `context`'s task recorded, when the task commits nothing: no pending write will carry it. */
    private def uncommitted(context: RunContext): Unit =
      if !context.usage.lostIsEmpty then
        failedUsage.updateAndGet(_.merge(context.usage.lost))
        failedSteps.addAndGet(context.usage.lostSteps): Unit

    private def failed(message: String): RunEvent = RunEvent.RunFailed(message, lostUsage, failedSteps.get)

    /**
     * When the claim was found lost, at any stage of the run, as a `System.nanoTime` value; set once. The
     * run's end is bounded from it: see [[lossDeadline]].
     */
    private val lostAt = AtomicReference[Option[Long]](None)

    /** Records that the claim is lost, now, unless it was recorded already. */
    private def markLost(): Unit = lostAt.compareAndSet(None, Some(System.nanoTime())): Unit

    /** Whether the claim has been found lost. */
    def claimWasLost: Boolean = lostAt.get.isDefined

    /**
     * When a run whose claim was found lost must have ended, as a `System.nanoTime` value: twice
     * `claims.ttl` after the loss. By then its admission has returned, its result is set, and its local
     * slot is free, whatever the store is doing.
     */
    def lossDeadline: Long = lostAt.get.getOrElse(System.nanoTime()) + lossBound

    /** Completed once the claim is found lost; an admission waiting on its claim's commit wakes on it. */
    private val lossSignal = new java.util.concurrent.CompletableFuture[Unit]()

    /**
     * What the heartbeat does when it finds the claim lost: until the run has a handle, it records
     * [[StopCause.ClaimLost]], which the loop checks before every superstep; the runtime then sets it to
     * the handle's `stop`, which also interrupts the run, as a cancel does.
     */
    @volatile var stopper: () => Unit = () => cause.compareAndSet(None, Some(StopCause.ClaimLost)): Unit

    /**
     * Records the current claim the store named and when the loss was found ([[lostAt]]), and fences the
     * committer, whatever the run is doing - even committing its outcome, when the stop is ignored - so
     * nothing it has not begun to write lands ([[Committer.fence]]). Then wakes an admission waiting on
     * its claim's commit ([[admit]]), and stops the run through [[stopper]].
     */
    private def claimLost(current: Option[FencingToken]): Unit =
      if current.nonEmpty then lostTo.compareAndSet(None, current): Unit
      markLost()
      // fenced before the loss is acted on, so no commit starts once the run is stopped or settled
      if committer.fence(GraphError.ClaimLost(threadId.value, token, lostTo.get)) then
        GraphRuntime.logger.warn(
          s"Run ${named(runId)} on thread '${threadId.value}' lost its claim with a commit in the store; it may still land"
        )
      lossSignal.complete(()): Unit
      stopper()

    // a commit refused as stale stops the run at once, as the heartbeat's loss does
    committer.onStale = stale => claimLost(stale.current)

    /** The token every commit of this run carries; set by [[admit]] before its first commit. */
    private def token: FencingToken = claim.map(_.token).getOrElse(FencingToken(0L))

    /**
     * Stops the heartbeat - waiting for a renewal in flight, so that none lands after the release - and
     * gives back the store claim, once; a no-op before the claim was granted. Never throws, and keeps
     * the caller's interrupt flag.
     *
     * A run whose claim was found lost - whatever it was doing then, finishing included - releases on a
     * detached thread, as [[stopAndRelease]] says; so does a run whose closing wait was given up on
     * ([[closedWithoutRunThread]]), whose store is stuck on a commit and may serialise the release behind it.
     */
    def releaseClaim(): Unit =
      claim match
        case Some(c) if claimReleased.compareAndSet(false, true) =>
          stopAndRelease(threadId, c.token, heartbeat, lostAt.get, detach = closeGivenUp.get)
        // no claim, or released already: a lost claim's heartbeat is not joined, as stopAndRelease says
        case _ => heartbeat.foreach(beat => if lostAt.get.isDefined then beat.halt() else beat.stop(): Unit)

    /**
     * The cut for the run's own subscriptions, set when the run is launched: a write its committer
     * abandoned that lands after the run stopped waiting is never given to them.
     */
    @volatile var abandonedWrites: Option[AbandonedWrites] = None

    /**
     * Completed once the run's closing wait ([[closeCommitter]]) has returned, however it ended: what the bound on
     * that wait ([[DefaultRunHandle.closeWithin]]) waits for.
     */
    val closed = new java.util.concurrent.CompletableFuture[Unit]()

    /**
     * Arms the bound on the run's closing wait; set by the runtime when the run is launched, to the handle's
     * [[DefaultRunHandle.closeWithin]]. Idempotent.
     */
    @volatile var closing: () => Unit = () => ()

    /** Set once the closing wait was given up on ([[closedWithoutRunThread]]): the claim is then released detached. */
    private val closeGivenUp = new AtomicBoolean(false)

    /** When the committer last made progress, while a commit is in flight ([[Committer.stalledSince]]). */
    def stalledSince: Option[Long] = committer.stalledSince

    /**
     * Closes the committer - for `Async`, waiting for its queue; for `OnExit`, making the exit commit - bounded
     * while the run holds its claim (#1919): if a commit stays in the store for [[closeBound]] with none returning,
     * the run is ended without waiting for it ([[closedWithoutRunThread]]). A lost claim bounds it already, as
     * [[DefaultRunHandle.endWithin]] does every end of a lost run.
     */
    private def closeCommitter(): Unit =
      if committer.drains then closing()
      Using.resource(new AutoCloseable { def close(): Unit = closed.complete(()): Unit })(_ => committer.close())

    /**
     * The run's result when its closing wait is given up on ([[DefaultRunHandle.closeWithin]]): a commit had been
     * in the store for [[closeBound]] while the run held its claim. Settled as a run that lost its claim is
     * ([[endedWithoutRunThread]]), but failing [[GraphError.CommitTimedOut]]: the committer is fenced and every
     * commit in flight counted as an orphan, an `Async` writer shut down without waiting
     * ([[Committer.settledWithoutRunThread]]), and the run's own subscriptions cut at what the hub has handed over,
     * so a commit that lands later never reaches them as the run's. The claim is then released on a detached
     * thread ([[releaseClaim]]): the store may serialise the release behind the commit it is stuck on, and once the
     * release lands it refuses that commit. A run whose claim was found lost meanwhile ends as
     * [[endedWithoutRunThread]] says instead. Never throws, and never waits on the store.
     */
    def closedWithoutRunThread(): RunResult[O] =
      if claimWasLost then endedWithoutRunThread()
      else
        closeGivenUp.set(true)
        // the orphan is refused as stale once the release lands: that is no loss to act on, the run is settled
        committer.onStale = _ => ()
        val timedOut = GraphError.CommitTimedOut(threadId.value, closeTimeout)
        GraphRuntime.logger.warn(
          s"Run ${named(runId)} on thread '${threadId.value}' ended without its closing commit, which had not returned after $closeTimeout; it may still land"
        )
        DefaultRunHandle
          .guarded {
            committer.settledWithoutRunThread(timedOut)
            abandonedWrites.foreach(cut => hub.atMark(threadId)(cut.abandonAfter))
          }
          .left
          .foreach(thrown =>
            GraphRuntime.logger
              .warn(s"Ending run '${runId.value}' on thread '${threadId.value}' without it failed", thrown)
          )
        RunResult.Failed(position._1.state, timedOut)

    /**
     * The run's result when it is settled without its run thread ([[DefaultRunHandle.endWithin]]): the
     * claim was found lost, and the run thread has not ended - stuck, say, in a store call that ignores
     * interrupts. The committer is fenced and orphaned, and an Async writer shut down without waiting
     * ([[Committer.settledWithoutRunThread]]), so nothing more of the run lands but what is in the store
     * already, the writer's thread ends once that returns, and the hub keeps its mark for the thread only
     * until then; the run's own subscriptions
     * are cut at what the hub has handed over ([[EventHub.atMark]]), so a write landing later - or one
     * whose hand-over is still reading a subscriber's missing events - never reaches them as the run's
     * ([[AbandonedWrites]]). Fails with [[GraphError.ClaimLost]], at the last position the run reached.
     * When the run thread does end, nothing it returns is used. Never throws.
     */
    def endedWithoutRunThread(): RunResult[O] =
      markLost()
      val lost = GraphError.ClaimLost(threadId.value, token, lostTo.get)
      DefaultRunHandle
        .guarded {
          committer.settledWithoutRunThread(lost)
          abandonedWrites.foreach(cut => hub.atMark(threadId)(cut.abandonAfter))
        }
        .left
        .foreach(thrown =>
          GraphRuntime.logger
            .warn(s"Ending run '${runId.value}' on thread '${threadId.value}' without it failed", thrown)
        )
      RunResult.Failed(position._1.state, lost)

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
          s"Run ${named(runId)} on thread '${threadId.value}' crashed after losing its claim",
          thrown
        )
        lostClaim(position._1, None)
      else
        DefaultRunHandle.guarded(closeCommitter()).left.foreach(thrown.addSuppressed)
        RunResult.Failed(position._1.state, GraphError.RunCrashed(threadId.value, thrown))

    /**
     * Drives supersteps. `position` advances only once the previous turn's commits are known to
     * have succeeded, so a run stopped after a failed commit names the last durable checkpoint.
     */
    @tailrec private def loop(
      execution: Execution,
      checkpointId: String,
      reused: Map[TaskId, ReusedWrite]
    ): RunResult[O] =
      committer.failure match
        // the store refused a commit as stale: another run took the thread over
        case Some(stale: GraphError.StaleClaim) => lostClaim(execution, stale.current)
        // a commit skipped because the claim was found lost ([[Committer.fence]])
        case Some(lost: GraphError.ClaimLost) => lostClaim(execution, lost.current)
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
                  // the digest the write was recorded with: never of the decoded result
                  case Some(write) => Right(Settled(task, write.result, write.digest))
                  // held at a breakpoint before it runs: nothing to record, and recovery decides again
                  case None if graph.holdsBefore(task, config) =>
                    Right(Settled(task, TaskResult.Held, CompiledGraph.HeldDigest))
                  case None => runTask(task, execution, checkpointId)
              })
            // a cancelled task cancels the run, even if an earlier task failed
            if outcomes.exists { case Left(_: CancelledError) => true; case _ => false } then cancelled()
            else
              val completed =
                outcomes.foldLeft[Result[Vector[Settled]]](Right(Vector.empty))((done, outcome) =>
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
            s"Run ${named(runId)} on thread '${threadId.value}' lost its claim while recording its failure: ${e.message}"
          )
        )
        GraphError.ClaimLost(threadId.value, token, stale.current.orElse(lostTo.get))
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
          val (status, event, outcome) = result match
            case suspended: RunResult.Suspended =>
              (CheckpointStatus.Suspended, RunEvent.RunSuspended(suspended.interrupts.map(_.id.value)), None)
            case RunResult.Completed(_, output, _) =>
              (CheckpointStatus.Completed, RunEvent.RunCompleted, Some(graph.outcomeOf(output)))
            case _ => (CheckpointStatus.Completed, RunEvent.RunCompleted, Some(RunOutcome.Answered))
          newCheckpoint(execution, Some(checkpointId), status, outcome) match
            case Left(error)                                                       => fail(execution, error)
            case Right(_) if !cause.compareAndSet(None, Some(StopCause.Finishing)) => cancelled()
            case Right(saved) =>
              val completed = status == CheckpointStatus.Completed
              if completed && !committer.drains then retainBeforeSettling(checkpointId)
              submit(saved, event)
              closeCommitter()
              committer.failure match
                case Some(e) => RunResult.Failed(execution.state, writeFailed(e, None))
                case None    =>
                  // Async or OnExit: the buffered commits have drained, so the terminal checkpoint is stored
                  if completed && committer.drains then retainBeforeSettling(saved.id)
                  result

    /**
     * Child retention before the run settles (design 4.41): once `lastCommitted`, the checkpoint of the run's last
     * superstep, is durable - the thread's latest in the store - it schedules no task, so every call the run made has
     * committed and none will run again, and every settled child is prunable ([[keepChildren]]). Run before the
     * terminal checkpoint commits, so a parent that gets no later turn is bounded too; a crash during it leaves the
     * thread `Running` at that checkpoint, and `recover` reaches this point again with nothing to run and repeats the
     * pass, which is idempotent. Under `Durability.Async` or `OnExit` that checkpoint is not yet durable there - a crash
     * could replay its calls - so [[end]] runs the pass instead once the run's buffered commits have drained, with
     * `lastCommitted` the stored terminal checkpoint, still under the run's claim (#2067); a drain that fails leaves it
     * to the next `start`. The pass also settles as abandoned, and so may prune, the earlier run's children that
     * admission skipped as busy and whose claims are free by now ([[abandonLater]], #2073); one still busy is left to
     * the next `start`. It never abandons a child of this run. A run a node blocked ([[block]]) runs it too, once its
     * closing `Failed` checkpoint is stored, with `lastCommitted` that checkpoint: a blocked turn is never recovered,
     * so its calls never run again (#2074). A run that fails runs no pass, since `recover` may replay its calls.
     */
    private def retainBeforeSettling(lastCommitted: String): Unit =
      val left = keepChildren(
        threadId,
        config,
        Some(lastCommitted),
        token,
        parentFence(threadId, token, () => lostAt.get.isDefined),
        abandon = abandonLater.contains
      )
      if left.nonEmpty then
        GraphRuntime.logger.debug(
          s"Retention on ${threadId.value} left ${left.size} busy abandoned children to the next start"
        )

    /**
     * Ends a run a task blocked: the superstep's updates are committed, so `execution` is the state the
     * thread keeps, and the closing checkpoint is [[CheckpointStatus.Failed]] with a
     * [[RunEvent.RunFailed]]. Like [[end]], it records [[StopCause.Finishing]] before submitting, so no
     * later cancel or expiry interrupts that commit; if one was recorded first, the run is stopped instead.
     * The caller gets `error` itself.
     */
    private def block(execution: Execution, parent: String, error: LLMError): RunResult[O] =
      newCheckpoint(execution, Some(parent), CheckpointStatus.Failed, Some(RunOutcome.blocked(error))) match
        case Left(storeError)                                                  => fail(execution, storeError)
        case Right(_) if !cause.compareAndSet(None, Some(StopCause.Finishing)) => cancelled()
        case Right(saved)                                                      =>
          // the blocked turn leaves the history with it: nothing of it stays readable or forkable
          submit(saved, failed(error.message), retractTurn = true)
          closeCommitter()
          committer.failure.fold[RunResult[O]] {
            // the closing checkpoint is stored and the blocked turn's calls never run again, so every settled child is
            // prunable, as at the next start (#2074); a failure, which `recover` may replay, runs no pass
            retainBeforeSettling(saved.id)
            RunResult.Failed(execution.state, error)
          }(e => RunResult.Failed(execution.state, writeFailed(e, Some(error))))

    /** Runs `task`, recording its write; its digest is that of the write exactly as recorded (design §4.24). */
    private def runTask(task: Task, execution: Execution, checkpointId: String): Result[Settled] =
      val sink = TaskSink(task, checkpointId)
      val context = new RunContext(
        config,
        RunPosition(threadId, runId, checkpointId, task.id, task.node, execution.superstep, claim.map(_.token)),
        sink,
        journal = effectJournal,
        owner = Some(task.arrivalId -> task.arrivalLineage),
        resumeDigest = task.resumeDigest,
        deadline = deadlineAt,
        admitted = admittedState
      )
      def settled(executed: Result[TaskResult]): Result[Settled] =
        executed.flatMap(result => graph.encodeWrite(checkpointId, task, result).map(result -> _)) match
          // no write and no event: `recover` runs the task again; its calls' usage goes on the run's terminal event
          case Left(cancelled: CancelledError) =>
            uncommitted(context)
            Left(cancelled)
          case Right(_) if Thread.currentThread().isInterrupted =>
            uncommitted(context)
            Left(CancelledError(s"task ${task.id.value}"))
          case Right((result, write)) =>
            val event = result match
              // a held task never reaches here: encodeWrite refuses it
              case TaskResult.Done(_) | TaskResult.Held => RunEvent.TaskCompleted
              case TaskResult.Parked(_, _, _)           => RunEvent.TaskSuspended(task.id.value)
              case TaskResult.Blocked(_, e)             => RunEvent.TaskFailed(e.message)
            committer.submit(
              Commit(token, None, Vector(write), draft(Some(checkpointId), Some(task), event) +: sink.customEvents)
            )
            Right(Settled(task, result, write.resultDigest.getOrElse(CompiledGraph.writeDigest(write))))
          case Left(error) =>
            uncommitted(context)
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
          uncommitted(context)
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
          Right(Settled(task, blocked, CompiledGraph.blockedDigest(error)))
        case executed => settled(executed)

    /**
     * The thread's effect records, for every task's [[ReplayGuard]]s (design §4.24). A record is written
     * at once, as a commit of its own fenced by the run's claim, whatever the durability mode - an intent
     * must be durable before its effect - and through the committer's fence, so a run whose claim was
     * found lost writes none. A write refused as stale stops the run at once, as a refused commit does.
     */
    private lazy val effectJournal: EffectJournal = new EffectJournal:
      def lookup(key: EffectKey): Result[Option[EffectRecord]] =
        Try(checkpointer.effect(threadId, key)).toResult.flatten
      def write(record: EffectRecord): Result[Unit] =
        val commit = Commit(token, effects = Vector(record))
        committer
          .begun(commit)(reason => Left(reason))(commitAndDeliver(threadId, commit))
          .left
          .map {
            case stale: GraphError.StaleClaim =>
              committer.onStale(stale)
              stale
            case other => other
          }
          .map(_ => ())

    /**
     * The run's next checkpoint. Every checkpoint carries the thread's [[RunOrigin]], as it carries its tenant; only the
     * terminal one that settles the run carries an `outcome`, so a later run's checkpoints clear it.
     */
    private def newCheckpoint(
      execution: Execution,
      parent: Option[String],
      status: CheckpointStatus,
      outcome: Option[RunOutcome] = None
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
          claim.map(_.token),
          origin,
          outcome
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
          closeStopped(
            execution,
            RunEvent.RunCancelled(lostUsage, failedSteps.get),
            GraphError.Cancelled(Some(threadId.value), Some(checkpointId))
          )
        case StopCause.Expired =>
          closeStopped(
            execution,
            RunEvent.RunTimedOut(lostUsage, failedSteps.get),
            GraphError.DeadlineExceeded(threadId.value, Some(checkpointId))
          )
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
      markLost()
      val lost = GraphError.ClaimLost(threadId.value, token, current.orElse(lostTo.get))
      // a write still in flight may land after the run's end-of-run barrier: it is cut from the run's
      // own subscriptions at what the hub has handed over by now - each hand-over reaches every
      // subscription at once - so it never reaches them as the run's
      if committer.abandon(lost) then abandonedWrites.foreach(cut => hub.atMark(threadId)(cut.abandonAfter))
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
          Commit(token, None, Vector.empty, Vector(draft(None, None, failed(error.message))))
        )
        stop(execution, error)

    /**
     * Closes the committer and fails the run. If closing reveals that a commit failed - an Async
     * queue, or OnExit's one exit commit - the run was not made durable, and the result says so
     * ([[GraphError.CheckpointWriteFailed]], keeping `error` as the run's own failure).
     */
    private def stop(execution: Execution, error: LLMError): RunResult[O] =
      closeCommitter()
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
      // once the claim is found lost, or released, a node that ignores the stop or keeps its context sends
      // nothing more: a later run - of the same id, perhaps - may hold the thread. Checked under the hub's lock,
      // so never after a later run's claim (#1918, #1737)
      def progress(name: String, version: Int, payload: ujson.Value): Unit =
        hub.live(threadId, liveEvent(name, version, payload), () => !claimWasLost && !claimReleased.get)
      // a child's start, remembered for a nested subscriber attaching mid-child (#2074): fenced as a nested event is,
      // so a child that outlives its parent's run never announces itself into a later run's subscriptions
      override def childStarted(child: ChildRef, name: String, version: Int, payload: ujson.Value): Unit =
        hub.childStarted(
          threadId,
          child,
          liveEvent(name, version, payload),
          () => !claimWasLost && !claimReleased.get
        )
      private def liveEvent(name: String, version: Int, payload: ujson.Value): StreamEvent.Live =
        StreamEvent.Live(
          threadId.value,
          runId.value,
          token,
          task.id.value,
          task.node.value,
          name,
          version,
          ujson.copy(payload)
        )
      // a child's event, forwarded from the child's run (design 4.47): fenced like progress, and also once the run has
      // released its claim, so a child that outlives its parent's run (it ignored a cancel past the drain) sends
      // nothing into a later run's subscriptions
      override def nested(event: StreamEvent.Nested): Unit =
        hub.nested(threadId, event, token, () => !claimWasLost && !claimReleased.get)
      // a timed-out delegating call's child (#2098), under its own claim, fenced by this run's
      override def settleChild(child: ThreadId, outcome: RunOutcome, message: String): Result[Option[RunId]] =
        settleUnreachable(child, threadId, token, outcome, message, config.withRunId(RunId.random()))
