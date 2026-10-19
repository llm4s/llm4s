package org.llm4s.agent.graph

import org.llm4s.agent.delegation.ChildRef
import org.llm4s.error.{ CancelledError, LLMError }
import org.llm4s.types.{ Result, TryOps }

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.{ Condition, ReentrantLock }
import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try, Using }

/** Runs `body` holding `lock`, releasing it on every exit, an `InterruptedException` included. */
private[agent] def withLock[A](lock: ReentrantLock)(body: => A): A =
  lock.lock()
  Using.resource(new AutoCloseable { def close(): Unit = lock.unlock() })(_ => body)

/**
 * [[withLock]], but an interrupt while waiting for `lock` ends the wait with an `InterruptedException`,
 * and `body` never runs.
 */
private[agent] def withLockInterruptibly[A](lock: ReentrantLock)(body: => A): A =
  lock.lockInterruptibly()
  Using.resource(new AutoCloseable { def close(): Unit = lock.unlock() })(_ => body)

/**
 * One lock per thread id, held while a commit on that thread is written and handed over (and while a
 * store watch of that thread queues what it read), so that delivery order is commit order on each
 * thread - the only order a subscription, which is to one thread, sees. A commit hung in the store
 * therefore holds up only commits on its own thread, never another thread's. A lock exists only while
 * some caller holds or waits for it, so idle threads cost nothing.
 */
final private[graph] class ThreadLocks:
  final private class Held:
    val lock  = new ReentrantLock()
    var users = 0 // changed only inside `locks.compute`, which serialises it

  private val locks = new java.util.concurrent.ConcurrentHashMap[String, Held]()

  /** Runs `body` holding `threadId`'s lock, releasing it on every exit, an `InterruptedException` included. */
  def withThreadLock[A](threadId: ThreadId)(body: => A): A = using(threadId)(held => withLock(held.lock)(body))

  /**
   * [[withThreadLock]], but an interrupt while waiting for the lock - behind a commit hung in the store,
   * say - ends the wait with an `InterruptedException`, and `body` never runs.
   */
  def withThreadLockInterruptibly[A](threadId: ThreadId)(body: => A): A =
    using(threadId)(held => withLockInterruptibly(held.lock)(body))

  /** Runs `f` with `threadId`'s lock entry, which exists while `f` runs. */
  private def using[A](threadId: ThreadId)(f: Held => A): A =
    val held = locks.compute(
      threadId.value,
      (_, existing) =>
        val entry = Option(existing).getOrElse(new Held)
        entry.users += 1
        entry
    )
    Using.resource(new AutoCloseable {
      def close(): Unit =
        locks.compute(
          threadId.value,
          (_, entry) =>
            entry.users -= 1
            if entry.users == 0 then null else entry
        ): Unit
    })(_ => f(held))

  /** How many thread ids have a lock; for tests. */
  private[graph] def size: Int = locks.size

/**
 * A run-scoped listener: one run's view of a subscription made for that run ([[RunHandle.subscribe]],
 * or the run's [[Observer]]). Its dispatcher calls [[runEnded]] - on the dispatcher thread, like any
 * listener call, but never with an event - when it reaches the run's end-of-run barrier, which
 * follows everything of the run queued to it. A listener that is not a `RunListener` - every
 * thread-scoped one - is never told.
 */
private[agent] trait RunListener extends (StreamEvent => Unit):
  /** The run `runId` has ended and everything of it queued to this listener has been delivered. */
  def runEnded(runId: RunId): Unit

/**
 * The events of run `runId` that land after the run gave up on them: a write still in the store when a
 * run that lost its claim stopped waiting for it - one its Async committer abandoned, or one the run's
 * own thread or a task was still making when the run was ended without it - may land after the run
 * has ended. Such events are an orphan of a run that has ended, not events it
 * delivered: the run's own listeners ([[RunListener]]) never receive them, live or replayed, while
 * thread-scoped subscribers receive them like any other durable event, in sequence. [[abandonAfter]]
 * sets the cut once, at the highest `seq` the hub had finished handing over on the thread when the run
 * stopped waiting ([[EventHub.atMark]]); every event of the run with a higher `seq` is an orphan - also
 * one of a commit in the store whose hand-over was still reading a subscriber's missing events then,
 * which reaches every subscription only after the run's barrier.
 */
final private[graph] class AbandonedWrites(runId: RunId):
  private val after = new java.util.concurrent.atomic.AtomicLong(Long.MaxValue)

  /** Marks every event of the run with `seq > last` as abandoned, unless a cut is already set. */
  def abandonAfter(last: Long): Unit = after.compareAndSet(Long.MaxValue, last): Unit

  /** Whether `record` is an event of the run that landed after it was abandoned. */
  def apply(record: EventRecord): Boolean = record.runId == runId.value && record.seq > after.get

private[graph] object AbandonedWrites:
  /** For a subscription not made for a run: nothing is abandoned. */
  val none: EventRecord => Boolean = _ => false

/**
 * Whose thread a subscription may deliver. A thread belongs to the tenant on its latest checkpoint, and
 * [[GraphRuntime.subscribe]] refuses another tenant's thread up front, but a thread can change hands
 * under its id after that: one that did not exist yet is created by another tenant's run, or one is
 * deleted and created again by another tenant through a runtime whose delete the subscription has not
 * yet learned of. So a scoped subscription screens every event before delivering it:
 *
 *  - an event that names its run's tenant - each of the events a run, a fork or a state update begins
 *    with ([[EventHub.tenantNamed]]) - is delivered only if that tenant is the subscriber's, and confirms
 *    the thread as the subscriber's;
 *  - any other event is delivered once the thread is confirmed; until then the thread's latest
 *    checkpoint is read and its tenant checked first. A [[StreamEvent.LiveGap]] carries nothing of
 *    the thread, and is delivered as it is.
 *
 * An event of another tenant ends the subscription, before that event, with
 * [[DisconnectReason.TenantMismatch]]: it delivers nothing of a thread it is not entitled to. A failed
 * read ends it as a failed replay does, and a thread with no checkpoint any more as a deleted one.
 */
private[graph] enum TenantScope:
  /** Delivers every event, unscreened: only the hub's own tests, which have no tenants, subscribe so. */
  case Unscoped

  /** Delivers only a thread of `tenantId`; `confirmed` if the thread is already known to be one. */
  case Of(tenantId: Option[String], confirmed: Boolean)

/** A subscription the [[EventHub]] dispatches: one that can be given a run's end-of-run barrier. */
private[graph] trait Dispatched extends Subscription:
  /**
   * Queues run `runId`'s end-of-run barrier behind everything queued so far, or - while the
   * subscription is still replaying - behind everything its switch to live catches up. Called once
   * the run has handed its last event to the hub, before it releases the thread claim - so no event of
   * a later run on the thread is queued ahead of it. A no-op unless
   * the listener is a [[RunListener]], and once the subscription is cancelled or lagging: a lagging
   * subscription ends with its `Disconnected` instead, so a scope never ends as if it had seen
   * everything when it has not.
   */
  def endOfRun(runId: RunId): Unit

  /** How many items the subscription's queue holds; for tests. */
  private[graph] def queued: Int

  /** Whether the dispatcher thread has ended, waiting for it at most `within`; for tests. */
  private[graph] def ended(within: scala.concurrent.duration.FiniteDuration): Boolean

/**
 * Delivers a runtime's events to subscribers. Each subscription has its own dispatcher: a bounded
 * queue drained in order by one virtual thread, the only thread its listener is called on. The
 * commit path hands events to every queue and returns without waiting on a listener.
 *
 * A run-scoped subscription ([[RunListener]]) also gets its run's end-of-run barrier
 * ([[Dispatched.endOfRun]]) once the run has handed over its last event, just before it releases the
 * thread and sets its result: a marker queued behind the run's last
 * event that is never passed to the listener as an event. Reaching it, the dispatcher calls
 * [[RunListener.runEnded]], which ends a scope whose run committed no terminal event. The barrier
 * takes one slot beyond `capacity`, so it never makes a subscriber lag; a subscriber already
 * lagging gets none and ends with its `Disconnected`.
 *
 * Durable events reach each subscriber in ascending `seq`, at most once, and only after the commit that numbered them.
 * Concurrently active subscriptions of this runtime that stay caught up see this runtime's own durable and live events
 * in one interleaving: every event handed to several of them has the same relative placement for each. A subscription
 * that attaches later misses earlier live events, and a slower one can lose live events or be disconnected as
 * `Lagging`; that guarantee does not cover those cases. A commit made by another runtime or process is the exception:
 * it reaches each subscription through that subscription's own store watch, through a hand-over's gap read, or, while
 * the subscription is still in its initial replay, directly from that replay, so where
 * it falls among this runtime's live events can differ per subscription (its `seq` order never does). The guarantee
 * holds when nothing outside this runtime commits to the thread; an external commit allows subscriptions to differ, not
 * necessarily makes them differ. The structural fix would be one ordered per-thread log (#1920). The queue holds up to
 * `capacity` durable events and, separately, up to
 * `capacity` live events and gap markers, so live traffic - a burst of text deltas the dispatcher
 * has not drained yet - never crowds out a durable event: only a subscriber more than `capacity`
 * durable events behind lags. A durable event that does not fit disconnects the subscriber as
 * lagging once what is already queued has been delivered. A live event is accepted only while two
 * of its slots are free, so a [[StreamEvent.LiveGap]] counting the live events dropped before it
 * always fits. Live events dropped before a durable event or a barrier ride in that item's own
 * slot ([[AfterGap]]) and are delivered as a `LiveGap` just before it, so a gap is reported where
 * it happened without taking a live slot. The queue therefore never holds more than `2 * capacity`
 * events and gap markers, plus one barrier per run that ended while they were queued. A lagging
 * subscriber with dropped live events still pending gets that `LiveGap` after its queue drains and
 * just before `Disconnected(lastSeq, Lagging)`.
 *
 * [[subscribe]] joins the thread's live set before it returns, so a subscription receives every live
 * event handed to the hub after that - also while its dispatcher is still replaying the log. During
 * the replay those live events are held, up to `capacity` of them with the same drop-and-count rule
 * (a dropped one is reported as a `LiveGap`), each marked with the highest durable `seq` handed to
 * the hub on the thread before it - the subscription starts from the hub's mark for the thread
 * ([[durable]] records it), so an event held before any commit reaches the subscription is still
 * placed. The dispatcher delivers a held event just before the first durable event with a higher
 * `seq` - one handed over after it - or, if no such event has been handed over, once the replay has
 * caught up; so live events keep their place among the durable events around them, as for a
 * subscription past its replay. A commit is written to the store before it is handed over, so the
 * replay can read one the hub has not handed over yet; it delivers only events the hub has handed
 * over and leaves the rest to the hand-over, which then queues them behind every live event sent
 * before it. Durable events are otherwise unaffected: the replay reads them from the store, so
 * holding live events never makes a subscriber lag.
 *
 * '''Which stored events have been handed over''' is decided only where no commit on the thread is
 * between its write and its hand-over. Every commit is written and handed over holding its thread's
 * commit lock, so that lock is where: either the dispatcher holds it, or it compares with a mark - the
 * top of a hand-over that has finished, which every event of a commit not yet handed over is above.
 * A subscription that joins with no mark - no run holds the thread, or none has handed anything over
 * since its claim was released, while the next run's first commit may already be in the store - takes
 * the thread's commit lock, on its own dispatcher thread and holding no lock of the hub's, before it
 * judges a page it read: waiting, interruptibly, for a commit being handed over to finish, so it never
 * replays a commit its other subscribers have not been handed ([[Dispatcher]]'s `settled`).
 *
 * The mark is kept only while the thread is in use: the runtime calls [[release]] when the claim is
 * released, after the run's last hand-over - or, if commits the run gave up on are still in flight
 * then (orphans, which may hang in the store), once the last of them returns - so the hub keeps no state
 * for idle threads. A mark kept for an orphan is still the top of a hand-over that has finished, and
 * the orphan is above it, so a subscription made meanwhile joins with it and never waits for the
 * orphan. A live event sent on a thread with no mark - only a node's own thread outliving its run
 * sends one - is held unmarked until the next commit is handed to the subscription, and placed just
 * before it.
 *
 * '''Commits made elsewhere''' ([[WatchPolicy]]). Another runtime or process sharing the store
 * commits without this hub knowing, so the hub hands over only this runtime's commits. While `watch`
 * is enabled, each live subscription therefore also watches the store, on a virtual thread of its own
 * ([[Dispatcher]]'s `watchLoop`): it waits in [[Checkpointer.awaitEventsAfter]] from the last event it
 * has queued, and queues what that returns holding the thread's commit lock ([[ThreadLocks]]) and
 * `hubLock`. Every commit of this runtime is written and handed over under its thread's commit lock,
 * so whatever the read found that this runtime wrote has been handed over by then, and is dropped by
 * `seq`: only other runtimes' events are queued this way, and this runtime's keep their place among its live events. They are queued
 * only while they fit beside what is queued - the rest stay in the store, read again on the next
 * call - so commits made elsewhere never make a subscriber lag. A subscription queues durable events
 * contiguously: a hand-over that finds events missing before it - committed elsewhere and not yet
 * read by the watch - reads them from the store first, under the thread's commit lock alone, so they
 * keep their place before it; if they do not fit, the subscriber lags as for any durable event. The
 * hand-over then queues the commit to every subscriber in one hold of `hubLock`, or to none ([[durable]]). No
 * store call is ever made holding `hubLock`, which every thread's hand-overs and the runtime's local
 * release take: a store that stalls a read or a commit holds up at most the subscription reading,
 * that thread's commits and, while the thread has no mark - a run's claim commit, before anything of
 * the run is handed over - the replay of a subscription to it that joins then, which waits for that
 * commit's hand-over to judge what it read ([[Dispatcher]]'s `settled`). A commit of a launched run
 * that the run gave up on never holds one up that way: the run's claim was handed over, so the thread
 * has a mark, and it is kept until that commit returns. Each of these waits lasts as long as the stalled call,
 * which only the store's own bound on a call limits ([[Checkpointer]], #1919). A failed watch
 * or gap read ends the subscription once its queue is delivered, with
 * `Disconnected(lastSeq, ReplayFailed(error))`.
 *
 * '''A deleted thread''' ([[threadDeleted]]). A subscription follows one thread, not its id. When
 * this runtime deletes the thread, every subscription to it in the live set once the store delete has
 * returned - made before the delete, or while it was in flight - ends once its queue is delivered, with
 * `Disconnected(lastSeq, ThreadDeleted)`, and takes nothing more. One in another
 * runtime learns of the delete from the store: a read from inside the deleted log is refused with
 * [[GraphError.ThreadDeleted]] - by its watch, its replay or a hand-over's gap read - and it ends the
 * same way. A subscription from `0` takes the thread under the id from its first event, which follows
 * a deleted thread's numbers. A subscription judged `Lagging` - every lag is judged by sequence numbers,
 * which a delete makes jump - is checked against the store before it ends, on its dispatcher thread,
 * and ends `ThreadDeleted` if its log was deleted.
 */
final private[graph] class EventHub(
  checkpointer: Checkpointer,
  commitLocks: ThreadLocks = ThreadLocks(),
  watch: WatchPolicy = WatchPolicy.disabled
):

  private val PageSize = 500

  /** Guards `subscribers`; commits hand events over under it, so each queue sees commit order. */
  private val hubLock = new ReentrantLock()

  /** Subscribed dispatchers, by thread, including those still replaying; guarded by `hubLock`. */
  private var subscribers = Map.empty[String, Vector[Dispatcher]]

  /**
   * The highest `seq` handed over on each thread a run holds, by thread; guarded by `hubLock`. A
   * run's claim is handed over before any of its live events is sent, so while a run sends live
   * events its thread has a mark; [[release]] removes it.
   */
  private var handed = Map.empty[String, Long]

  /**
   * The delegated children a task of the run holding each thread announced and whose call has not yet ended, by thread
   * then child thread: the live `TaskStarted` that announced each, and the child it names (design 4.47, #2074). A
   * subscription made with `nested = true` is given, at attach, that `TaskStarted` and one `Nested(child, LiveGap(0))`
   * for each, since it missed the child's earlier live events. Guarded by `hubLock`; [[release]] forgets the thread's.
   */
  private var running = Map.empty[String, Map[String, (StreamEvent.Live, ChildRef)]]

  /** Starts a dispatcher that replays events with `seq > afterSeq`, then delivers new ones. */
  def subscribe(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit,
    abandoned: EventRecord => Boolean,
    scope: TenantScope,
    nested: Boolean = false
  ): Result[Dispatched] =
    val dispatcher = new Dispatcher(threadId, afterSeq, capacity, listener, abandoned, scope, nested = nested)
    // joined before the dispatcher starts, so no live event sent after this returns is missed, and
    // marked with what the hub has handed over so far, so every live event it holds is placed
    withLock(hubLock) {
      dispatcher.seedHanded(handed.get(threadId.value))
      join(threadId, dispatcher)
      // a child running now has sent live events this subscription never gets: its start, then a gap (#2074)
      if nested then
        running.getOrElse(threadId.value, Map.empty).values.foreach { (started, child) =>
          dispatcher.offerLive(started, started.token)
          dispatcher.offerLive(StreamEvent.Nested(child, StreamEvent.LiveGap(0)), started.token)
        }
    }
    Try(dispatcher.start()).toResult.left
      .map { error =>
        leave(threadId, dispatcher)
        error
      }
      .map(_ => dispatcher)

  /**
   * A dispatcher for a run's observer that joins the thread's live set now, with no replay, and is not
   * yet running: it delivers everything committed from its run's claim on, and every live event sent
   * from then on. The caller holds the thread exclusively in this runtime, so the next commit it makes
   * is that claim - but a commit of a run that gave up on it ([[AbandonedWrites]]) may still be handed
   * over first, if the store applied it before the claim was granted. Such a commit is not the run's,
   * nor the observer's place in the log, even when its run had the same `RunId` - an id may be reused,
   * a token never is. So until a commit fenced by the run's own claim ([[Observation.claimed]], matched by
   * [[EventRecord.token]]) is handed to it, the dispatcher skips every commit, and every live event,
   * which no node of the run can have sent yet: it delivers what a subscription from just before the
   * claim ([[RunHandle.subscribe]]) does. After that it still skips a live event of an older claim than
   * its run's ([[StreamEvent.Live.token]]), which a node of a lost run that ignores the interrupt could
   * send under the same `RunId` - [[live]] refuses one sent once the loss is found, as every in-runtime
   * successor's claim is. A later run's, of a newer claim, it delivers. [[Observation.start]] starts it; [[Observation.abandon]]
   * removes it, delivering nothing.
   */
  def observe(
    threadId: ThreadId,
    capacity: Int,
    listener: StreamEvent => Unit,
    abandoned: EventRecord => Boolean,
    scope: TenantScope,
    nested: Boolean = false
  ): Observation =
    val dispatcher =
      new Dispatcher(
        threadId,
        afterSeq = 0L,
        capacity,
        listener,
        abandoned,
        scope,
        preJoined = true,
        nested = nested
      )
    withLock(hubLock)(join(threadId, dispatcher))
    new Observation(dispatcher)

  /** A dispatcher joined by [[observe]], to be started or abandoned exactly once. */
  final class Observation private[EventHub] (dispatcher: Dispatcher):
    /**
     * The run's claim was granted with `token`: the observer takes its place in the log at the first
     * commit fenced by it. Called before that claim's commit begins - so before it can be handed over -
     * and holding no lock of the hub's.
     */
    def claimed(token: FencingToken): Observation =
      dispatcher.claimed(token)
      this

    /** Starts delivery; `lastSeq` is what a `Disconnected` names if nothing durable is delivered. */
    def start(lastSeq: Long): Dispatched =
      dispatcher.startObserved(lastSeq)
      dispatcher

    /** Leaves the live set; the listener is never called. */
    def abandon(): Unit = dispatcher.cancel()

  /** How many dispatchers are in the thread's live set; for tests. */
  private[graph] def liveCount(threadId: ThreadId): Int =
    withLock(hubLock)(subscribers.getOrElse(threadId.value, Vector.empty).size)

  /** A test hook run on a dispatcher thread just before it waits for its thread's commit lock. */
  @volatile private[graph] var settling: ThreadId => Unit = _ => ()

  /**
   * A test hook run by an observer's store watch that found its dispatcher not yet anchored, just before
   * it waits to be; a no-op by default.
   */
  @volatile private[graph] var awaitingAnchor: ThreadId => Unit = _ => ()

  /** Running store watches, by thread; guarded by `hubLock`. */
  private var watching = Map.empty[String, Int]

  /** How many store watches are running for the thread; for tests. */
  private[graph] def watchCount(threadId: ThreadId): Int = withLock(hubLock)(watching.getOrElse(threadId.value, 0))

  private def watched(threadId: ThreadId, delta: Int): Unit = withLock(hubLock) {
    watching = watching.updatedWith(threadId.value)(n => Some(n.getOrElse(0) + delta).filter(_ > 0))
  }

  /**
   * Hands committed `records` to the thread's subscribers; never blocks on a listener. The caller holds
   * the thread's commit lock ([[ThreadLocks]]), so no other hand-over or store watch of the thread queues
   * meanwhile. A hand-over is one step: under one hold of `hubLock` it raises the thread's mark and
   * queues the commit - with, ahead of it, whatever each subscriber is missing before it - to every
   * subscriber at once, or to none. So a subscription never delivers a commit that another has not been
   * handed yet, and [[atMark]] sees each hand-over as whole or not begun.
   *
   * What subscribers are missing - events committed elsewhere and not yet read by their watch - is
   * read from the store first, holding no lock of the hub's: a store read that stalls holds up only
   * this thread's commits, never the hub - another thread's hand-overs, a subscription's switch to live,
   * or [[release]], which the runtime calls holding the lock every admission and release takes. One read,
   * from the lowest missing `seq`, serves every subscriber missing events. A subscriber that joined, or
   * fell further behind, while it read - a switch to live made meanwhile - sends it back to read the rest
   * before anything is queued; each such read starts where no earlier one did, so this ends. A read that fails,
   * or is interrupted (the flag is set again), ends the subscribers that needed it, as a failed read
   * does ([[Dispatcher]]'s `fail`), and the commit is still handed to the others.
   */
  def durable(threadId: ThreadId, records: Vector[EventRecord]): Unit =
    if records.nonEmpty then
      val first = records.iterator.map(_.seq).min
      val top   = records.iterator.map(_.seq).max
      @tailrec def handOver(reads: Vector[GapRead]): Unit =
        val missing = withLock(hubLock) {
          val dispatchers = subscribers.getOrElse(threadId.value, Vector.empty)
          val unread      = dispatchers.flatMap(_.unread(records, reads))
          if unread.isEmpty then
            // every gap is read: the whole hand-over, to every subscriber, under this one hold
            handed = handed.updated(threadId.value, handed.get(threadId.value).fold(top)(math.max(_, top)))
            dispatchers.foreach(_.offerDurable(records, reads))
          unread
        }
        if missing.nonEmpty then
          val from = missing.iterator.map(_.after).min
          val room = missing.iterator.map(_.room).max
          handOver(reads :+ GapRead(from, readBetween(threadId, from, first, room)))
      handOver(Vector.empty)

  /**
   * What a subscriber `after` the given `seq` is missing before a commit, with `room` durable slots
   * free: at most `room - 1` of the missing events fit beside the commit's first.
   */
  final private case class Gap(after: Long, room: Int)

  /**
   * The store's events between `from` and a commit's first, read for a hand-over ([[readBetween]]):
   * every one of them, or `None` if more were found than fit, or the failed read.
   */
  final private case class GapRead(from: Long, read: Result[Option[Vector[EventRecord]]]):
    /** Whether this read answers a subscriber that has queued up to `after`. */
    def answers(after: Long): Boolean = from == after || (from < after && read.forall(_.isDefined))

  /**
   * The events with `after < seq < before`, read from the store page by page, holding no lock of the
   * hub's; `None` once more than `room - 1` of them are found, so that they and the event at `before`
   * cannot all fit. An interrupt is a failed read, with the flag set again.
   */
  private def readBetween(
    threadId: ThreadId,
    after: Long,
    before: Long,
    room: Int
  ): Result[Option[Vector[EventRecord]]] =
    @tailrec def page(from: Long, found: Vector[EventRecord]): Result[Option[Vector[EventRecord]]] =
      if found.size >= room then Right(None)
      else if from >= before - 1 then Right(Some(found))
      else
        val limit = math.min(math.min(PageSize, room - found.size).toLong, before - 1 - from).toInt
        CancelledError.attempt(s"read of thread ${threadId.value}")(
          Try(checkpointer.eventsAfter(threadId, from, limit)).toResult.flatten
        ) match
          case Left(error)                 => Left(error)
          case Right(read) if read.isEmpty => Right(Some(found))
          case Right(read)                 => page(read.last.seq, found ++ read.filter(_.seq < before))
    page(after, Vector.empty)

  /**
   * Forgets the thread's hand-over mark. The runtime calls it when a run's claim on the thread is
   * released - after the run's last hand-over, before another run can claim the thread - and when a
   * thread is deleted, so the hub holds a mark only for threads a run holds.
   */
  def release(threadId: ThreadId): Unit =
    withLock(hubLock) {
      handed = handed - threadId.value
      running = running - threadId.value
    }

  /**
   * Runs `f` with the highest `seq` handed over on the thread (0 if none), holding `hubLock`, so no
   * hand-over is made while it runs. A hand-over is queued to every subscriber in one step under that
   * lock ([[durable]]), so one made before is at or below the value and queued everywhere, and one not
   * yet made - still reading subscribers' missing events from the store, say - is above it and queued
   * nowhere: a run that stops waiting for it counts it as abandoned ([[AbandonedWrites]]) on every
   * subscription alike, live and on replay.
   */
  def atMark(threadId: ThreadId)(f: Long => Unit): Unit =
    withLock(hubLock)(f(handed.getOrElse(threadId.value, 0L)))

  /**
   * The store has deleted the thread: every subscription to it in the live set now ends with
   * [[DisconnectReason.ThreadDeleted]] after what it has queued. Called once the store delete has
   * returned, so a subscription that joined while the delete was in flight - which may have replayed
   * the deleted log - is ended too, never left following whichever thread its replay happened to read.
   * The caller still holds the thread, so no run of the next thread under the id has committed in this
   * runtime: a subscription made in the moment between the delete and this call has seen nothing of a
   * new thread, and is ended as well; it subscribes again to follow one.
   */
  def threadDeleted(threadId: ThreadId): Unit =
    withLock(hubLock)(subscribers.getOrElse(threadId.value, Vector.empty)).foreach(_.threadDeleted())

  /** Whether the hub holds a hand-over mark for the thread; for tests. */
  private[graph] def marked(threadId: ThreadId): Boolean = withLock(hubLock)(handed.contains(threadId.value))

  /**
   * Offers live progress to the thread's subscribers, if `current` - whether the sending run still holds
   * its claim, as far as it knows - is true; never blocks on a listener. `current` is read holding
   * `hubLock`, which every hand-over holds, so the event is either queued everywhere ahead of a later
   * run's claim or not at all: a run of this runtime is admitted to a thread only once an earlier run's
   * loss has been found, so a node of the lost run that ignores the interrupt and outlives it sends
   * nothing after the next run's claim (#1918). `current` must not block, nor take a lock.
   */
  def live(threadId: ThreadId, event: StreamEvent.Live, current: () => Boolean = () => true): Unit =
    withLock(hubLock) {
      if current() then subscribers.getOrElse(threadId.value, Vector.empty).foreach(_.offerLive(event, event.token))
    }

  /**
   * Offers a delegated child's event (design 4.47), sent by a task of the run holding `threadId` under the claim
   * `token`, to the thread's subscribers made with `nested = true`, as [[live]] offers live progress: in their live
   * slots, behind what is queued, a `LiveGap` on overflow, never stored.
   */
  def nested(threadId: ThreadId, event: StreamEvent.Nested, token: FencingToken, current: () => Boolean): Unit =
    withLock(hubLock) {
      if current() then
        subscribers.getOrElse(threadId.value, Vector.empty).filter(_.nested).foreach(_.offerLive(event, token))
    }

  /**
   * Offers `started`, the live `TaskStarted` of `child`, to the thread's subscribers as [[live]] does, and remembers the
   * child as running until [[childEnded]], so that a nested subscription made meanwhile is told of it ([[running]]).
   */
  def childStarted(threadId: ThreadId, child: ChildRef, started: StreamEvent.Live, current: () => Boolean): Unit =
    withLock(hubLock) {
      if current() then
        subscribers.getOrElse(threadId.value, Vector.empty).foreach(_.offerLive(started, started.token))
        val children = running.getOrElse(threadId.value, Map.empty).updated(child.threadId.value, started -> child)
        running = running.updated(threadId.value, children)
    }

  /** The call that ran `child` on the thread has ended: a nested subscription made from now on is not told of it. */
  def childEnded(threadId: ThreadId, child: ThreadId): Unit =
    withLock(hubLock) {
      running = running.updatedWith(threadId.value)(_.map(_ - child.value).filter(_.nonEmpty))
    }

  /** Joins the live set; the caller holds `hubLock`. */
  private def join(threadId: ThreadId, dispatcher: Dispatcher): Unit =
    subscribers = subscribers.updated(threadId.value, subscribers.getOrElse(threadId.value, Vector.empty) :+ dispatcher)

  private def leave(threadId: ThreadId, dispatcher: Dispatcher): Unit =
    withLock(hubLock) {
      subscribers = subscribers.updatedWith(threadId.value)(_.map(_.filterNot(_ eq dispatcher)).filter(_.nonEmpty))
    }

  /** How a dispatcher stops: silently when cancelled, otherwise with a final `Disconnected`. */
  private enum End:
    case Cancelled
    case Disconnect(reason: DisconnectReason)

  /** A run's end-of-run barrier in a dispatcher's queue; never passed to a listener as an event. */
  final private case class RunEnd(runId: RunId)

  /**
   * A durable event or barrier queued after `dropped` live events were discarded: delivered as
   * `LiveGap(dropped)`, then `item`. It takes `item`'s slot alone, so reporting a gap where it
   * happened never needs a live slot - however often dropped live events and durable commits
   * alternate while the dispatcher is held, the queue stays within its bounds.
   */
  final private case class AfterGap(dropped: Int, item: StreamEvent.Durable | RunEnd)

  private type Queued = StreamEvent | RunEnd | AfterGap

  /**
   * A live event or gap marker held while its dispatcher replays, `after` the highest durable `seq`
   * handed to the hub before it; `None` if the hub had no mark for the thread (no run held it) and
   * none has been handed to the dispatcher since, until the first one is. An unplaced event was sent
   * after the dispatcher joined, so after every event its replay finds to be history.
   */
  final private case class Held(after: Option[Long], event: LiveItem | StreamEvent.LiveGap)

  /** What takes a live slot: live progress, and a child's event forwarded to a nested subscriber. */
  private type LiveItem = StreamEvent.Live | StreamEvent.Nested

  /**
   * One subscriber. It is in the live set from [[subscribe]] on, but until it has replayed it only
   * holds live events ([[Held]]) and notes the `seq` of durable ones, which the replay reads from the
   * store. It replays the log directly to the listener, each held live event just before the first
   * durable event handed over after it, then, under `hubLock`, queues what was handed over since the
   * last read with the live events held among it, and switches to queueing what the hub hands it -
   * commits hand events over under the same lock, so none lands between the last read and the
   * switch, and the `seq` check drops any already read. Both stop at an event written to the store
   * but not yet handed over ([[handedOver]]): the hand-over queues it, behind the live events sent
   * before it. After that it drains its queue, and watches the store for commits made elsewhere
   * (`watchLoop`). The listener is never called while a lock is held. A `preJoined` dispatcher
   * ([[observe]]) never replays, and only drains and watches; it takes its place in the log from the
   * first commit fenced by its run's claim (`observing`, the token) handed to it - the claim's own -
   * skipping every commit and live event handed over before it, and its watch waits until then; it skips
   * a live event of an older claim than its run's, a lost run's, from then on too.
   */
  final private class Dispatcher(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit,
    abandoned: EventRecord => Boolean,
    scope: TenantScope,
    preJoined: Boolean = false,
    /** Whether the subscriber takes delegated children's events ([[StreamEvent.Nested]]). */
    val nested: Boolean = false
  ) extends Dispatched:
    private val lock                = new ReentrantLock()
    private val notEmpty: Condition = lock.newCondition()

    /** Signalled when a listener call ends. */
    private val idle: Condition = lock.newCondition()

    /** Signalled when a `preJoined` dispatcher is anchored, so its store watch starts watching at once. */
    private val anchoring: Condition = lock.newCondition()

    // guarded by `lock`; `lastQueuedSeq` is also advanced by replay, before the dispatcher joins
    private val queue         = new java.util.ArrayDeque[Queued]()
    private var lastQueuedSeq = afterSeq
    // what `queue` holds, by kind: each has its own `capacity`, so live events never crowd out durable
    // ones; a durable event's `AfterGap` counts as durable, a barrier as neither
    private var durableQueued       = 0
    private var liveQueued          = 0
    private var droppedLive         = 0
    private var lagging             = false
    @volatile private var cancelled = false

    /**
     * Why the subscription stops taking events, other than lagging: reading the store - the watch, or
     * a hand-over's gap - failed, or the thread was deleted. Ends the subscription like `lagging`.
     */
    private var failed = Option.empty[DisconnectReason]

    /** Whether the subscription stops taking events, to end once its queue is delivered. */
    private def halted: Boolean = lagging || failed.isDefined

    /**
     * Whether `lastQueuedSeq` is a place in the thread's log: from the start for a subscription, which
     * replays from `afterSeq`; for a `preJoined` one, from the first commit fenced by `observing`.
     */
    private var anchored = !preJoined

    /**
     * For a `preJoined` dispatcher, the token of its run's claim once granted ([[Observation.claimed]]);
     * guarded by `lock`. Until a commit carrying it is handed over, none is the run's.
     */
    private var observing = Option.empty[FencingToken]

    /** Its run's claim was granted with `token`; see [[Observation.claimed]]. */
    def claimed(token: FencingToken): Unit = withLock(lock) { observing = Some(token) }

    /**
     * Whether `event` was sent under an older claim than this observer's run's: by a node of a lost run,
     * which ignored the interrupt and outlived it, whatever its `RunId` (#1918). Holding `lock`.
     */
    private def superseded(token: FencingToken): Boolean = observing.exists(_.value > token.value)

    /** The store watch, once started; and whether the dispatcher has ended, which stops it. */
    @volatile private var watcher  = Option.empty[Thread]
    @volatile private var finished = false

    /**
     * Whether the dispatcher has replayed and queues what the hub hands it; until then a barrier
     * waits in `pendingEnds`, and live events in `held`.
     */
    private var joined      = preJoined
    private var pendingEnds = Vector.empty[RunId]

    // while replaying: live events held, oldest first, with their `after` ascending; at most
    // `capacity` of them, gap markers included. `handedSeq` is the highest durable `seq` handed to
    // the hub on the thread: the hub's mark when the dispatcher joined, then what it is handed.
    private var held      = Vector.empty[Held]
    private var handedSeq = Option.empty[Long]

    /**
     * Starts from the hub's mark for the thread; called under `hubLock`, as the dispatcher joins. A
     * mark is the top of a hand-over that has finished, so taking it needs no commit lock: every event
     * at or below it was handed over or committed elsewhere, and every commit of this runtime not yet
     * handed over is above it - commits are numbered in the order they are written, and each is
     * written and handed over under its thread's commit lock before the next is written. With no mark
     * the dispatcher knows nothing yet, and learns what it reads is history only holding that lock
     * ([[settled]]).
     */
    def seedHanded(mark: Option[Long]): Unit = withLock(lock) { handedSeq = mark }

    /**
     * The highest `seq` read from the store that was found, holding the thread's commit lock, to be
     * history - handed over, or committed elsewhere ([[settled]]); guarded by `lock`. Set only by a
     * dispatcher that had no mark when it read the page.
     */
    private var history = 0L

    /**
     * Whether the event `seq`, read from the store, has been handed to the hub: one written but not
     * yet handed over is left to the hand-over, so that it follows the live events sent before it.
     * Every page read is [[settled]] before it is judged, so an event is counted as handed over only
     * at or below a mark, or found to be history holding the commit lock - never one a commit has
     * written and not yet handed over.
     */
    private def handedOver(seq: Long): Boolean = withLock(lock)(seq <= history || handedSeq.exists(seq <= _))

    /**
     * `page`, once the dispatcher can tell which of its events have been handed over ([[handedOver]]).
     * With a mark (`handedSeq`) it can at once. With none - the hub had no mark for the thread when the
     * dispatcher joined (no run held it), and nothing has been handed to it since - an event a commit
     * has written but not yet handed over looks like one handed over before the dispatcher joined: the
     * write is visible first, and there is no mark to compare it with. So it decides holding the
     * thread's commit lock, which every commit of this runtime holds from its write to its hand-over:
     * then no commit is between the two, so every event of the page was handed over or committed
     * elsewhere - history, up to the page's last `seq`, whether or not a hand-over was noted while it
     * waited (which gives it a mark as well). Live events it holds keep their place: one held before any
     * hand-over was noted was sent after the dispatcher joined, so after everything handed over before
     * that, and goes just before the first commit handed to it; one held after a noted hand-over goes
     * just after that commit, before the events above it, which were committed elsewhere. It waits for
     * the lock holding no lock of the hub's, and interruptibly: `cancel` ends a wait behind a commit hung
     * in the store.
     */
    private def settled(page: Vector[EventRecord]): Either[End, Vector[EventRecord]] =
      val known = page.lastOption.forall(last => withLock(lock)(handedSeq.isDefined || last.seq <= history))
      if known then Right(page)
      else
        settling(threadId)
        CancelledError.catchInterrupt(commitLocks.withThreadLockInterruptibly(threadId)(withLock(lock) {
          history = math.max(history, page.last.seq)
        })) match
          case _ if cancelled => Left(End.Cancelled)
          case Right(_)       => Right(page)
          case Left(_) =>
            Left(End.Disconnect(DisconnectReason.ReplayFailed(CancelledError(s"replay of thread ${threadId.value}"))))

    /** A listener call is in progress; set, with `cancelled` checked, under `lock`. */
    private var delivering = false

    /** The highest durable `seq` the listener returned from; written only by the dispatcher thread. */
    @volatile private var lastDeliveredSeq = afterSeq

    /** Set by `start`, before `subscribe` returns the subscription that can cancel it. */
    @volatile private var thread: Option[Thread] = None

    def start(): Unit =
      thread = Some(Thread.ofVirtual().name(s"llm4s-subscriber-${threadId.value}").start(() => run()))

    /** Starts a pre-joined dispatcher: it never replays, and has been in the live set since `observe`. */
    def startObserved(lastSeq: Long): Unit =
      lastDeliveredSeq = lastSeq
      start()

    /**
     * Stops the dispatcher. Off the dispatcher thread, waits for a listener call in progress to end
     * (interrupting it first), so that once this returns no call is running and none will start.
     * From the listener itself it neither interrupts nor waits: it returns at once, the rest of the
     * call runs with the flag as it was, and the dispatcher stops when the call returns.
     */
    def cancel(): Unit =
      withLock(lock) {
        cancelled = true
        notEmpty.signalAll()
      }
      leave(threadId, this)
      watcher.foreach(_.interrupt())
      thread.filterNot(_ eq Thread.currentThread()).foreach { dispatcher =>
        dispatcher.interrupt()
        withLock(lock) {
          @tailrec def awaitIdle(): Unit =
            if delivering then
              idle.awaitUninterruptibly()
              awaitIdle()
          awaitIdle()
        }
      }

    /**
     * What this subscriber is missing before `records`, a commit this runtime has just made, that no
     * read in `reads` answers; called holding `hubLock`. Nothing for a subscriber still replaying,
     * which only notes the commit, for one that stops taking events, for one with nothing missing,
     * and for one so far behind that the missing events and the commit cannot all fit, which lags.
     */
    def unread(records: Vector[EventRecord], reads: Vector[GapRead]): Option[Gap] =
      withLock(lock) {
        missingBefore(records).filterNot(gap => reads.exists(_.answers(gap.after)))
      }

    /** The gap before `records` this subscriber must have read, holding `lock`. */
    private def missingBefore(records: Vector[EventRecord]): Option[Gap] =
      if cancelled || !joined || !anchored || halted then None
      else
        records.iterator.map(_.seq).find(_ > lastQueuedSeq).flatMap { next =>
          // the missing events and this one cannot all fit: behind by more than `capacity`. From `0`
          // the gap cannot be told from the numbers - under a deleted thread's id the log starts past
          // the deleted thread's - so it is read, at most as far as fits
          val tooFar = lastQueuedSeq > 0 && durableQueued + (next - lastQueuedSeq) > capacity
          Option.when(next > lastQueuedSeq + 1 && !tooFar)(Gap(lastQueuedSeq, capacity - durableQueued))
        }

    /**
     * Queues `records`, a commit this runtime has just made, in order, behind the events before them;
     * called holding the thread's commit lock and `hubLock`, as one step of a hand-over to every
     * subscriber ([[EventHub.durable]]). Missing events - committed elsewhere and not yet read by the
     * watch - are queued ahead of them from the read in `reads` that answers this subscriber; if they
     * cannot all fit, the subscriber lags, and if the read failed, it ends. A replaying subscriber only
     * notes them. An observer not yet anchored skips a commit not fenced by its run's claim ([[observe]]).
     */
    def offerDurable(records: Vector[EventRecord], reads: Vector[GapRead]): Unit =
      withLock(lock) {
        if cancelled then ()
        else if !joined then records.foreach(record => noteDurable(record.seq))
        else if !anchored && !observing.exists(token => records.exists(_.token == token)) then ()
        else
          if !anchored then
            lastQueuedSeq = records.iterator.map(_.seq).min - 1
            anchored = true
            anchoring.signalAll()
          val gapped = records.iterator.map(_.seq).find(_ > lastQueuedSeq).exists(_ > lastQueuedSeq + 1)
          if halted || !gapped then records.foreach(queueDurable)
          else
            reads.find(_.answers(lastQueuedSeq)).map(_.read) match
              // `queueDurable` queues only the event that follows the last queued, so the read's events
              // and the commit's are queued in order, and none twice
              case Some(Right(Some(found))) => (found ++ records).foreach(queueDurable)
              case Some(Left(error))        => fail(error)
              case _                        =>
                // more are missing than fit beside the commit: behind by more than `capacity`
                lagging = true
                notEmpty.signal()
      }

    /**
     * Ends the subscription, once its queue is delivered, with `ReplayFailed(error)` - or `ThreadDeleted`
     * if the store refused the read as one from inside a deleted thread's log; holding `lock`.
     */
    private def fail(error: LLMError): Unit = stop(EventHub.disconnectFor(error))

    /** Ends the subscription, once its queue is delivered, with `reason`; holding `lock`. */
    private def stop(reason: DisconnectReason): Unit =
      if !cancelled && !halted then
        failed = Some(reason)
        notEmpty.signal()

    /**
     * The thread was deleted through this runtime: ends the subscription with `ThreadDeleted` once
     * what it has queued is delivered - or, while it is still replaying, at its next read.
     */
    def threadDeleted(): Unit = withLock(lock)(stop(DisconnectReason.ThreadDeleted))

    /**
     * While replaying, a commit's event is read from the store, not queued: it only marks where the
     * live events held so far go. Live events dropped before it are held as a gap marker just before
     * it: merged into the last held marker if that is already there, otherwise a marker of their
     * own if a slot is free, otherwise merged into the last held marker, which moves to just before
     * this event - the earlier drops are then reported late, never early, as a dropped event riding
     * a durable one's slot ([[AfterGap]]) is. With every slot taken by live events they are left
     * pending, to be reported with the next item.
     */
    private def noteDurable(seq: Long): Unit =
      if droppedLive > 0 then
        held.lastOption match
          case Some(Held(after, StreamEvent.LiveGap(n))) if after == handedSeq =>
            held = held.init :+ Held(after, StreamEvent.LiveGap(n + droppedLive))
            droppedLive = 0
          case _ if held.size < capacity =>
            held = held :+ Held(handedSeq, StreamEvent.LiveGap(droppedLive))
            droppedLive = 0
          case Some(Held(_, StreamEvent.LiveGap(n))) =>
            held = held.init :+ Held(handedSeq, StreamEvent.LiveGap(n + droppedLive))
            droppedLive = 0
          case _ => ()
      // the first commit handed over: everything held so far precedes it
      if handedSeq.isEmpty then held = held.map(h => h.copy(after = h.after.orElse(Some(seq - 1))))
      handedSeq = handedSeq.fold(Some(seq))(s => Some(math.max(s, seq)))

    /**
     * Queues a committed event behind what is queued, holding `lock`; see the class description. Only
     * the next event in the log is queued, so a subscriber never skips one: an event already queued is
     * dropped, and so is one past a gap, which the store is read again for.
     */
    private def queueDurable(record: EventRecord): Unit =
      if !cancelled && !halted && follows(record.seq) then
        if durableQueued >= capacity then lagging = true
        else
          queue.add(afterPendingGap(StreamEvent.Durable(record)))
          durableQueued += 1
          lastQueuedSeq = record.seq
        notEmpty.signal()

    /**
     * Whether `seq` is the next event of the log, holding `lock`: the one after the last queued, or,
     * with nothing queued yet from `0`, the first the store has - which, under a deleted thread's id,
     * follows the deleted thread's numbers. Every caller offers the store's events in order, from the
     * first after the last queued, so the first offered from `0` is the log's first.
     */
    private def follows(seq: Long): Boolean = seq == lastQueuedSeq + 1 || (lastQueuedSeq == 0 && seq > 0)

    /** Offers a live item sent under the claim `token`. */
    def offerLive(event: LiveItem, token: FencingToken): Unit = withLock(lock) {
      // an observer not yet anchored: no node of its run has run, so the event is another run's; and once
      // anchored, an event of an older claim than its run's is a lost run's, whatever its `RunId` (#1918)
      if cancelled || !anchored || superseded(token) then ()
      else if !joined then hold(event)
      else if !halted then
        // the gap marker needs one slot and the event one
        if capacity - liveQueued >= 2 then
          flushGap()
          queue.add(event)
          liveQueued += 1
        else droppedLive += 1
        notEmpty.signal()
    }

    /** Holds `event` while replaying, under the same two-free-slots rule as the queue's live slots. */
    private def hold(event: LiveItem): Unit =
      if capacity - held.size >= 2 then
        if droppedLive > 0 then
          held = held :+ Held(handedSeq, StreamEvent.LiveGap(droppedLive))
          droppedLive = 0
        held = held :+ Held(handedSeq, event)
      else droppedLive += 1

    /** Takes, holding `lock`, the held events that precede the durable event `seq`. */
    private def takeHeldBefore(seq: Long): Vector[StreamEvent] =
      val (before, rest) = held.span(_.after.exists(_ < seq))
      held = rest
      before.map(_.event)

    /** Moves `events` into the queue's live slots, holding `lock`; a lagging subscriber counts them as dropped. */
    private def queueHeld(events: Vector[StreamEvent]): Unit =
      if halted then
        droppedLive += events.map {
          case StreamEvent.LiveGap(n) => n
          case _                      => 1
        }.sum
      else if !cancelled then
        events.foreach(queue.add)
        liveQueued += events.size
        if events.nonEmpty then notEmpty.signal()

    /** Queues the pending gap as a marker of its own, in a live slot; `offerLive` has made room. */
    private def flushGap(): Unit =
      if droppedLive > 0 then
        queue.add(StreamEvent.LiveGap(droppedLive))
        liveQueued += 1
        droppedLive = 0

    /** `item`, carrying the pending gap if there is one, so the gap takes no slot of its own. */
    private def afterPendingGap(item: StreamEvent.Durable | RunEnd): Queued =
      if droppedLive == 0 then item
      else
        val gapped = AfterGap(droppedLive, item)
        droppedLive = 0
        gapped

    def queued: Int = withLock(lock)(queue.size)

    private[graph] def ended(within: scala.concurrent.duration.FiniteDuration): Boolean =
      thread.forall(_.join(java.time.Duration.ofNanos(within.toNanos)))

    def endOfRun(runId: RunId): Unit =
      listener match
        case _: RunListener =>
          withLock(lock) {
            if joined then queueEnd(runId) else pendingEnds = pendingEnds :+ runId
          }
        case _ => ()

    /**
     * Queues `runId`'s barrier, holding `lock`, unless cancelled or lagging; it carries a pending
     * gap, so the listener learns of live events of the run dropped before it. Exempt from
     * `capacity`.
     */
    private def queueEnd(runId: RunId): Unit =
      if !cancelled && !halted then
        queue.add(afterPendingGap(RunEnd(runId)))
        notEmpty.signal()

    private def run(): Unit =
      // leaves the live set, and stops the watch, on every exit, including one by a fatal error
      Using.resource(new AutoCloseable {
        def close(): Unit =
          finished = true
          watcher.foreach(_.interrupt())
          leave(threadId, Dispatcher.this)
      }) { _ =>
        val ready = if preJoined then Right(()) else replay().flatMap(_ => switchToLive())
        val ending = ready.fold(
          identity,
          _ =>
            startWatching()
            drain()
        )
        ending match
          case End.Disconnect(reason) =>
            leave(threadId, this)
            reportPendingGap(reason, classified(reason)).foreach(ending =>
              call(() => listener(StreamEvent.Disconnected(lastDeliveredSeq, ending))): Unit
            )
          case End.Cancelled => ()
      }

    /**
     * The reason a subscription ends, `Lagging` checked against the store. Every lag is judged by the
     * numbers - a gap, or a full queue - and a thread deleted and created again by another runtime
     * leaves a gap of the deleted thread's numbers, which only the store can tell from a real backlog.
     * So, on the dispatcher thread, once what was queued is delivered and only then, the store is read
     * once after the last queued event: [[GraphError.ThreadDeleted]] there makes the end
     * `ThreadDeleted`. Any other answer, a failed read or an interrupt (`cancel`, which also stops the
     * `Disconnected`) leaves `Lagging`. Never on the commit path: a hand-over that finds a subscriber
     * behind only marks it.
     */
    private def classified(reason: DisconnectReason): DisconnectReason =
      if reason != DisconnectReason.Lagging then reason
      else
        val from = withLock(lock)(lastQueuedSeq)
        CancelledError.catchInterrupt(Try(checkpointer.eventsAfter(threadId, from, 1)).toResult.flatten) match
          case Right(Left(error)) =>
            EventHub.disconnectFor(error) match
              case DisconnectReason.ThreadDeleted => DisconnectReason.ThreadDeleted
              case _                              => reason
          case _ => reason

    /**
     * A lagging subscriber can end with live events dropped since its last gap marker - the durable
     * event that did not fit would have carried them. They are reported as a
     * [[StreamEvent.LiveGap]] just before `Disconnected`, so the count is never lost. `reason` is why
     * the subscription stopped, `reported` the reason [[classified]] gives it. Returns the reason the
     * final `Disconnected` carries: `reported`, or [[DisconnectReason.ListenerFailed]] if
     * the listener throws on that gap, as on any other event; `None` - no `Disconnected` - if the
     * subscription was cancelled meanwhile.
     */
    private def reportPendingGap(reason: DisconnectReason, reported: DisconnectReason): Option[DisconnectReason] =
      val dropped = withLock(lock) {
        val pending = if reason == DisconnectReason.Lagging || failed.isDefined then droppedLive else 0
        droppedLive = 0
        pending
      }
      if dropped == 0 then Some(reported)
      else
        deliver(StreamEvent.LiveGap(dropped)) match
          case Right(_)                      => Some(reported)
          case Left(End.Disconnect(failure)) => Some(failure)
          case Left(End.Cancelled)           => None

    /**
     * Delivers committed events directly until a read finds none, or finds one not yet handed over
     * ([[handedOver]]), which [[switchToLive]] leaves to its hand-over.
     */
    @tailrec private def replay(): Either[End, Unit] =
      read() match
        case Left(stop)                  => Left(stop)
        case Right(page) if page.isEmpty => Right(())
        case Right(page)                 =>
          // `Right(true)`: replayed the page; `Right(false)`: stopped at an event not handed over yet
          page.foldLeft[Either[End, Boolean]](Right(true))((done, record) =>
            done.flatMap { more =>
              if !more || record.seq <= lastQueuedSeq then Right(more)
              else if !handedOver(record.seq) then Right(false)
              else
                // live events held from before this commit go first
                withLock(lock)(takeHeldBefore(record.seq))
                  .foldLeft[Either[End, Unit]](Right(()))((sent, event) => sent.flatMap(_ => deliver(event)))
                  .flatMap(_ => deliver(StreamEvent.Durable(record)))
                  .map(_ => withLock(lock) { lastQueuedSeq = record.seq })
                  .map(_ => true)
            }
          ) match
            case Right(true)  => replay()
            case Right(false) => Right(())
            case Left(stop)   => Left(stop)

    /**
     * Queues whatever was handed over since the last read - reading on while pages come back full -
     * with the live events held among it, then the rest of the held events, and switches to queueing
     * what the hub hands over. Each page is read from the store holding no lock, and queued under
     * `hubLock`, which commits hand over under; the switch is made under the same hold only if every
     * event handed over by then is queued - one handed over after the page was read sends it back to
     * read again - so none lands between the last read and the switch. An event read but not yet handed
     * over ([[handedOver]]) ends the catch-up: it is handed to the dispatcher once it is in the live
     * set, behind the held events. Live events dropped while replaying and not yet held as a gap marker
     * were dropped after every held event, so they are reported after all of these, not with the first
     * event caught up. No store read under a lock, and no listener call: a read that stalls holds up
     * only this subscription, never the hub.
     */
    private def switchToLive(): Either[End, Unit] =
      // drops not yet held as a gap marker, taken before each page is queued
      var droppedWhileReplaying = 0
      @tailrec def catchUp(): Either[End, Unit] =
        read() match
          case Left(stop) => Left(stop)
          case Right(page) =>
            val switched = withLock(hubLock)(withLock(lock) {
              droppedWhileReplaying += droppedLive
              droppedLive = 0
              val caughtUp = page.takeWhile(record => handedOver(record.seq))
              caughtUp.foreach { record =>
                if record.seq > lastQueuedSeq then queueHeld(takeHeldBefore(record.seq))
                queueDurable(record)
              }
              val fullPage = page.size == PageSize && caughtUp.size == page.size
              // a commit handed over after the page was read: the page cannot have reached it
              val readTooSoon = handedSeq.exists(h => h > lastQueuedSeq && page.lastOption.forall(_.seq < h))
              if !halted && (fullPage || readTooSoon) then false
              else
                // live events held past the last commit, then barriers of runs that ended during the
                // replay, go behind everything caught up
                queueHeld(held.map(_.event))
                held = Vector.empty
                droppedLive += droppedWhileReplaying
                joined = true
                pendingEnds.foreach(queueEnd)
                pendingEnds = Vector.empty
                true
            })
            if switched then Right(()) else catchUp()
      catchUp()

    /** Takes queued events in order; ends when cancelled, or when lagging and drained. */
    @tailrec private def drain(): End =
      val next: Either[End, Option[Queued]] =
        CancelledError.catchInterrupt(withLock(lock) {
          awaitWork()
          if cancelled then Left(End.Cancelled)
          else
            Option(queue.poll())
              .map { item =>
                item match
                  case _: StreamEvent.Durable              => durableQueued -= 1
                  case AfterGap(_, _: StreamEvent.Durable) => durableQueued -= 1
                  case _: RunEnd | AfterGap(_, _: RunEnd)  => ()
                  case _                                   => liveQueued -= 1
                Some(item)
              }
              .toRight(End.Disconnect(failed.getOrElse(DisconnectReason.Lagging)))
        }) match
          case Right(taken) => taken
          // only `cancel` interrupts this thread; anything else is ignored
          case Left(_) => if cancelled then Left(End.Cancelled) else Right(None)
      next match
        case Left(stop)  => stop
        case Right(None) => drain()
        case Right(Some(item)) =>
          val delivered = item match
            case AfterGap(dropped, rest) => deliver(StreamEvent.LiveGap(dropped)).flatMap(_ => deliverItem(rest))
            case RunEnd(runId)           => reachEnd(runId)
            case event: StreamEvent      => deliver(event)
          delivered match
            case Right(_)   => drain()
            case Left(stop) => stop

    private def deliverItem(item: StreamEvent.Durable | RunEnd): Either[End, Unit] =
      item match
        case RunEnd(runId)              => reachEnd(runId)
        case event: StreamEvent.Durable => deliver(event)

    /** At `runId`'s barrier: tells a [[RunListener]], as a listener call, that its run has ended. */
    private def reachEnd(runId: RunId): Either[End, Unit] =
      listener match
        case run: RunListener => outcome(call(() => run.runEnded(runId)))
        case _                => Right(())

    /** Waits, holding `lock`, until there is something to deliver or a reason to stop. */
    @tailrec private def awaitWork(): Unit =
      if !cancelled && !halted && queue.isEmpty then
        notEmpty.await()
        awaitWork()

    /**
     * Calls the listener; a throw ends the subscription, and a delivered durable event advances
     * `lastDeliveredSeq`. A run's own listener is never given an event of the run that landed after the
     * run abandoned it ([[AbandonedWrites]]); checked here, as it is delivered, so the cut applies
     * whenever the event was read or queued.
     */
    private def deliver(event: StreamEvent): Either[End, Unit] =
      event match
        case StreamEvent.Durable(record) if listener.isInstanceOf[RunListener] && abandoned(record) => Right(())
        case _ =>
          screened(event).flatMap(_ =>
            outcome(call(() => listener(event))).map { _ =>
              event match
                case StreamEvent.Durable(record) => lastDeliveredSeq = record.seq
                case _                           => ()
            }
          )

    /** Whether the thread is known to be the subscriber's tenant's ([[TenantScope]]); dispatcher thread only. */
    private var confirmed = scope match
      case TenantScope.Unscoped         => true
      case TenantScope.Of(_, confirmed) => confirmed

    /**
     * `event` may be delivered: it is of the subscriber's tenant's thread ([[TenantScope]]). Otherwise the
     * subscription ends, before it, with [[DisconnectReason.TenantMismatch]], or as a failed read of the
     * thread's latest checkpoint does. Called on the dispatcher thread holding no lock, as the listener
     * is, so a read that stalls holds up only this subscription; `cancel` interrupts it.
     */
    private def screened(event: StreamEvent): Either[End, Unit] =
      scope match
        case TenantScope.Unscoped => Right(())
        case TenantScope.Of(tenant, _) =>
          val named = event match
            case StreamEvent.Durable(record) => EventHub.tenantNamed(record.event)
            case _                           => None
          named match
            case Some(owner)                                     => owned(owner == tenant)
            case None if confirmed                               => Right(())
            case None if event.isInstanceOf[StreamEvent.LiveGap] => Right(())
            // the thread's owner now, not necessarily the event's: a delete and recreate by the subscriber's
            // own tenant inside this one read would confirm an event of the previous tenant. Accepted as a
            // residual (design record 4.21): closing it needs an incarnation on every event
            case None =>
              CancelledError.catchInterrupt(Try(checkpointer.latest(threadId)).toResult.flatten) match
                case _ if cancelled          => Left(End.Cancelled)
                case Right(Right(Some(now))) => owned(now.checkpoint.tenantId == tenant)
                case Right(Right(None))      => Left(End.Disconnect(DisconnectReason.ThreadDeleted))
                case Right(Left(error))      => Left(End.Disconnect(EventHub.disconnectFor(error)))
                case Left(_) =>
                  Left(
                    End.Disconnect(DisconnectReason.ReplayFailed(CancelledError(s"read of thread ${threadId.value}")))
                  )

    /** Confirms the thread as the subscriber's if `matches`, and ends the subscription otherwise. */
    private def owned(matches: Boolean): Either[End, Unit] =
      if matches then
        confirmed = true
        Right(())
      else Left(End.Disconnect(DisconnectReason.TenantMismatch))

    /** How a listener call ended: on, or stopped - cancelled, or the listener threw. */
    private def outcome(called: Option[Either[InterruptedException, Try[Unit]]]): Either[End, Unit] =
      called match
        case None                        => Left(End.Cancelled)
        case Some(_) if cancelled        => Left(End.Cancelled)
        case Some(Right(Success(_)))     => Right(())
        case Some(Right(Failure(cause))) => Left(End.Disconnect(DisconnectReason.ListenerFailed(cause)))
        case Some(Left(interrupted))     => Left(End.Disconnect(DisconnectReason.ListenerFailed(interrupted)))

    /**
     * Calls the listener unless cancelled - checked, and the call marked as in progress, atomically
     * under `lock`, so a `cancel` that returns has either prevented the call or waited for it.
     * `None` if cancelled first.
     */
    private def call(body: () => Unit): Option[Either[InterruptedException, Try[Unit]]] =
      val admitted = withLock(lock) {
        if !cancelled then delivering = true
        delivering
      }
      Option.when(admitted) {
        Using.resource(new AutoCloseable {
          def close(): Unit = withLock(lock) {
            delivering = false
            idle.signalAll()
          }
        })(_ => CancelledError.catchInterrupt(Try(body())))
      }

    /** Starts the store watch, unless `watch` is disabled; on the dispatcher thread, once it is live. */
    private def startWatching(): Unit =
      if watch.enabled && !cancelled then
        watched(threadId, 1)
        val started = Try(
          Thread
            .ofVirtual()
            .name(s"llm4s-watch-${threadId.value}")
            .start { () =>
              Using.resource(new AutoCloseable { def close(): Unit = watched(threadId, -1) })(_ =>
                CancelledError.catchInterrupt(watchLoop()): Unit
              )
            }
        )
        started.fold(_ => watched(threadId, -1), thread => watcher = Some(thread))
        // stopped meanwhile: the interrupt that would have stopped it found no thread
        if cancelled || finished then watcher.foreach(_.interrupt())

    /**
     * Watches the store for commits made through another runtime or process: waits in
     * [[Checkpointer.awaitEventsAfter]] from the last event queued and queues what it returns, until
     * the dispatcher ends. An interrupt - from `cancel` or the dispatcher's end - stops it.
     */
    @tailrec private def watchLoop(): Unit =
      val from = withLock(lock) {
        if cancelled || finished || halted then None else Some(Option.when(anchored)(lastQueuedSeq))
      }
      from match
        case None       => ()
        case Some(None) =>
          // an Observer's dispatcher, before its run's claim is handed to it: woken as soon as it is, and
          // otherwise every `pollInterval`, to check again; an interrupt - `cancel`, the end - stops it
          awaitingAnchor(threadId)
          withLock(lock) {
            if !anchored && !cancelled && !finished && !halted then
              anchoring.awaitNanos(watch.pollInterval.toNanos): Unit
          }
          watchLoop()
        case Some(Some(after)) =>
          Try(checkpointer.awaitEventsAfter(threadId, after, PageSize, watch.pollInterval)).toResult.flatten match
            case _ if cancelled || finished => ()
            case Left(_: CancelledError)    => ()
            case Left(error) =>
              withLock(lock)(fail(error))
            case Right(found) =>
              val stuck = found.nonEmpty && commitLocks.withThreadLockInterruptibly(threadId)(
                withLock(hubLock)(withLock(lock)(queueWatched(found)))
              )
              // what did not fit stays in the store until the listener has caught up; read it again later
              if stuck then TimeUnit.NANOSECONDS.sleep(watch.pollInterval.toNanos)
              watchLoop()

    /**
     * Queues, in order, the events the watch read that follow the last queued, while they fit: the
     * durable slots are never overfilled, so the watch never makes a subscriber lag. Returns whether
     * the watch should wait before reading again: it stopped for want of room, or at an event that
     * does not follow the last queued, which a store never returns but which must not make the watch
     * spin. Holding the thread's commit lock, `hubLock` and `lock`.
     */
    private def queueWatched(found: Vector[EventRecord]): Boolean =
      @tailrec def next(rest: List[EventRecord]): Boolean = rest match
        case Nil                                           => false
        case _ if cancelled || halted                      => false
        case record :: more if record.seq <= lastQueuedSeq => next(more)
        case record :: _ if !follows(record.seq)           => true
        case _ if durableQueued >= capacity                => true
        case record :: more =>
          queueDurable(record)
          next(more)
      next(found.toList)

    /**
     * The next page after `lastQueuedSeq`; a failed or interrupted read ends the subscription, and so
     * does a delete of the thread through this runtime ([[threadDeleted]]) while it replays.
     */
    private def read(): Either[End, Vector[EventRecord]] =
      val (from, stopped) = withLock(lock)((lastQueuedSeq, failed))
      stopped match
        case Some(reason) => Left(End.Disconnect(reason))
        case None         => readFrom(from).flatMap(settled)

    private def readFrom(from: Long): Either[End, Vector[EventRecord]] =
      CancelledError.catchInterrupt(
        Try(checkpointer.eventsAfter(threadId, from, PageSize)).toResult.flatMap(identity)
      ) match
        case _ if cancelled     => Left(End.Cancelled)
        case Right(Right(page)) => Right(page)
        case Right(Left(error)) => Left(End.Disconnect(EventHub.disconnectFor(error)))
        case Left(_) =>
          Left(End.Disconnect(DisconnectReason.ReplayFailed(CancelledError(s"replay of thread ${threadId.value}"))))

private[graph] object EventHub:
  /**
   * The tenant `event` records for its run, if it is one of the events that begin a run, a fork or a
   * state update - each records the tenant it was made for ([[RunEvent]]); `None` for any other event.
   */
  def tenantNamed(event: RunEvent): Option[Option[String]] = event match
    case RunEvent.RunStarted(tenant, _)         => Some(tenant)
    case RunEvent.RunRecovered(_, tenant, _, _) => Some(tenant)
    case RunEvent.RunResumed(_, tenant, _)      => Some(tenant)
    case RunEvent.StateUpdated(_, _, tenant, _) => Some(tenant)
    case RunEvent.ThreadForked(_, _, tenant, _) => Some(tenant)
    case _                                      => None

  /** How a failed read of the store ends a subscription: `ThreadDeleted` for a deleted thread's log. */
  def disconnectFor(error: LLMError): DisconnectReason = error match
    case _: GraphError.ThreadDeleted => DisconnectReason.ThreadDeleted
    case other                       => DisconnectReason.ReplayFailed(other)
