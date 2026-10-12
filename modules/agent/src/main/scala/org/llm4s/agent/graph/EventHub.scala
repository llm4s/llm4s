package org.llm4s.agent.graph

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
 * A run-scoped listener: one run's view of a subscription made for that run ([[RunHandle.subscribe]],
 * or the run's [[Observer]]). Its dispatcher calls [[runEnded]] - on the dispatcher thread, like any
 * listener call, but never with an event - when it reaches the run's end-of-run barrier, which
 * follows everything of the run queued to it. A listener that is not a `RunListener` - every
 * thread-scoped one - is never told.
 */
private[agent] trait RunListener extends (StreamEvent => Unit):
  /** The run `runId` has ended and everything of it queued to this listener has been delivered. */
  def runEnded(runId: RunId): Unit

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
 * Durable events reach each subscriber in ascending `seq`, at most once, and only after the commit
 * that numbered them. The queue holds up to `capacity` durable events and, separately, up to
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
 * The mark is kept only while a run holds the thread: the runtime calls [[release]] when the claim
 * is released, after the run's last hand-over, so the hub keeps no state for idle threads. A live
 * event sent on a thread no run holds - only a node's own thread outliving its run sends one - is
 * held unmarked until the next commit is handed to the subscription, and placed just before it.
 *
 * '''Commits made elsewhere''' ([[WatchPolicy]]). Another runtime or process sharing the store
 * commits without this hub knowing, so the hub hands over only this runtime's commits. While `watch`
 * is enabled, each live subscription therefore also watches the store, on a virtual thread of its own
 * ([[Dispatcher]]'s `watchLoop`): it waits in [[Checkpointer.awaitEventsAfter]] from the last event it
 * has queued, and queues what that returns holding `commitLock` and `hubLock`. Every commit of this
 * runtime is written and handed over under `commitLock`, so whatever the read found that this
 * runtime wrote has been handed over by then, and is dropped by `seq`: only other runtimes' events
 * are queued this way, and this runtime's keep their place among its live events. They are queued
 * only while they fit beside what is queued - the rest stay in the store, read again on the next
 * call - so commits made elsewhere never make a subscriber lag. A subscription queues durable events
 * contiguously: a hand-over that finds events missing before it - committed elsewhere and not yet
 * read by the watch - reads them from the store first, under the same locks, so they keep their
 * place before it; if they do not fit, the subscriber lags as for any durable event. A failed watch
 * or gap read ends the subscription once its queue is delivered, with
 * `Disconnected(lastSeq, ReplayFailed(error))`.
 */
final private[graph] class EventHub(
  checkpointer: Checkpointer,
  commitLock: ReentrantLock = new ReentrantLock(),
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

  /** Starts a dispatcher that replays events with `seq > afterSeq`, then delivers new ones. */
  def subscribe(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit
  ): Result[Dispatched] =
    val dispatcher = new Dispatcher(threadId, afterSeq, capacity, listener)
    // joined before the dispatcher starts, so no live event sent after this returns is missed, and
    // marked with what the hub has handed over so far, so every live event it holds is placed
    withLock(hubLock) {
      dispatcher.seedHanded(handed.get(threadId.value))
      join(threadId, dispatcher)
    }
    Try(dispatcher.start()).toResult.left
      .map { error =>
        leave(threadId, dispatcher)
        error
      }
      .map(_ => dispatcher)

  /**
   * A dispatcher that joins the thread's live set now, with no replay, and is not yet running: it
   * queues everything committed or sent on the thread from this moment. The caller holds the thread
   * exclusively, so the next commit is its run's claim. [[Observation.start]] starts it;
   * [[Observation.abandon]] removes it, delivering nothing.
   */
  def observe(threadId: ThreadId, capacity: Int, listener: StreamEvent => Unit): Observation =
    val dispatcher = new Dispatcher(threadId, afterSeq = 0L, capacity, listener, preJoined = true)
    withLock(hubLock)(join(threadId, dispatcher))
    new Observation(dispatcher)

  /** A dispatcher joined by [[observe]], to be started or abandoned exactly once. */
  final class Observation private[EventHub] (dispatcher: Dispatcher):
    /** Starts delivery; `lastSeq` is what a `Disconnected` names if nothing durable is delivered. */
    def start(lastSeq: Long): Dispatched =
      dispatcher.startObserved(lastSeq)
      dispatcher

    /** Leaves the live set; the listener is never called. */
    def abandon(): Unit = dispatcher.cancel()

  /** How many dispatchers are in the thread's live set; for tests. */
  private[graph] def liveCount(threadId: ThreadId): Int =
    withLock(hubLock)(subscribers.getOrElse(threadId.value, Vector.empty).size)

  /** Running store watches, by thread; guarded by `hubLock`. */
  private var watching = Map.empty[String, Int]

  /** How many store watches are running for the thread; for tests. */
  private[graph] def watchCount(threadId: ThreadId): Int = withLock(hubLock)(watching.getOrElse(threadId.value, 0))

  private def watched(threadId: ThreadId, delta: Int): Unit = withLock(hubLock) {
    watching = watching.updatedWith(threadId.value)(n => Some(n.getOrElse(0) + delta).filter(_ > 0))
  }

  /** Offers committed `records` to the thread's subscribers; never blocks on a listener. */
  def durable(threadId: ThreadId, records: Vector[EventRecord]): Unit =
    if records.nonEmpty then
      withLock(hubLock) {
        val top = records.iterator.map(_.seq).max
        handed = handed.updated(threadId.value, handed.get(threadId.value).fold(top)(math.max(_, top)))
        subscribers.getOrElse(threadId.value, Vector.empty).foreach(d => records.foreach(d.offerDurable))
      }

  /**
   * Forgets the thread's hand-over mark. The runtime calls it when a run's claim on the thread is
   * released - after the run's last hand-over, before another run can claim the thread - and when a
   * thread is deleted, so the hub holds a mark only for threads a run holds.
   */
  def release(threadId: ThreadId): Unit =
    withLock(hubLock) {
      handed = handed - threadId.value
    }

  /** Whether the hub holds a hand-over mark for the thread; for tests. */
  private[graph] def marked(threadId: ThreadId): Boolean = withLock(hubLock)(handed.contains(threadId.value))

  /** Offers live progress to the thread's subscribers; never blocks on a listener. */
  def live(threadId: ThreadId, event: StreamEvent.Live): Unit =
    withLock(hubLock) {
      subscribers.getOrElse(threadId.value, Vector.empty).foreach(_.offerLive(event))
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
   * none has been handed to the dispatcher since, until the first one is.
   */
  final private case class Held(after: Option[Long], event: StreamEvent.Live | StreamEvent.LiveGap)

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
   * first commit handed to it - its run's claim - and its watch waits until then.
   */
  final private class Dispatcher(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit,
    preJoined: Boolean = false
  ) extends Dispatched:
    private val lock                = new ReentrantLock()
    private val notEmpty: Condition = lock.newCondition()

    /** Signalled when a listener call ends. */
    private val idle: Condition = lock.newCondition()

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

    /** Why reading the store - the watch, or a hand-over's gap - failed; ends the subscription like `lagging`. */
    private var failed = Option.empty[LLMError]

    /** Whether the subscription stops taking events, to end once its queue is delivered. */
    private def halted: Boolean = lagging || failed.isDefined

    /**
     * Whether `lastQueuedSeq` is a place in the thread's log: from the start for a subscription, which
     * replays from `afterSeq`; for a `preJoined` one, from the first commit handed to it.
     */
    private var anchored = !preJoined

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

    /** Starts from the hub's mark for the thread; called under `hubLock`, as the dispatcher joins. */
    def seedHanded(mark: Option[Long]): Unit = withLock(lock) { handedSeq = mark }

    /**
     * Whether the event `seq`, read from the store, has been handed to the hub: one written but not
     * yet handed over is left to the hand-over, so that it follows the live events sent before it.
     * With no mark - no run held the thread when the dispatcher joined, and nothing has been handed
     * to it since - everything read is history, and counts as handed over.
     */
    private def handedOver(seq: Long): Boolean = withLock(lock)(handedSeq.forall(seq <= _))

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
     * Offers a commit this runtime has just made; called holding `commitLock` and `hubLock`. A live
     * subscription queues it after the events before it: any missing - committed through another
     * runtime and not yet read by the watch - are read from the store first and queued ahead of it,
     * or make the subscriber lag if they do not fit. A replaying one only notes it.
     */
    def offerDurable(record: EventRecord): Unit =
      val missing = withLock(lock) {
        if cancelled then None
        else if !joined then
          noteDurable(record.seq)
          None
        else
          if !anchored then
            lastQueuedSeq = record.seq - 1
            anchored = true
          if record.seq <= lastQueuedSeq + 1 || halted then
            queueDurable(record)
            None
          else if durableQueued + (record.seq - lastQueuedSeq) > capacity then
            // the missing events and this one cannot all fit: behind by more than `capacity`
            lagging = true
            notEmpty.signal()
            None
          else Some(lastQueuedSeq)
      }
      // `lastQueuedSeq` of a live dispatcher moves only under `hubLock`, which the caller holds
      missing.foreach { after =>
        val between = readBetween(after, record.seq)
        withLock(lock) {
          between match
            case Right(found) => (found :+ record).foreach(queueDurable)
            case Left(error)  => fail(error)
        }
      }

    /** The events with `after < seq < before`, read from the store page by page. */
    private def readBetween(after: Long, before: Long): Result[Vector[EventRecord]] =
      @tailrec def page(from: Long, found: Vector[EventRecord]): Result[Vector[EventRecord]] =
        if from >= before - 1 then Right(found)
        else
          val limit = math.min(PageSize.toLong, before - 1 - from).toInt
          Try(checkpointer.eventsAfter(threadId, from, limit)).toResult.flatten match
            case Left(error)                 => Left(error)
            case Right(read) if read.isEmpty => Right(found)
            case Right(read)                 => page(read.last.seq, found ++ read.filter(_.seq < before))
      page(after, Vector.empty)

    /** Ends the subscription, once its queue is delivered, with `ReplayFailed(error)`; holding `lock`. */
    private def fail(error: LLMError): Unit =
      if !cancelled && !halted then
        failed = Some(error)
        notEmpty.signal()

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
      if !cancelled && !halted && record.seq == lastQueuedSeq + 1 then
        if durableQueued >= capacity then lagging = true
        else
          queue.add(afterPendingGap(StreamEvent.Durable(record)))
          durableQueued += 1
          lastQueuedSeq = record.seq
        notEmpty.signal()

    def offerLive(event: StreamEvent.Live): Unit = withLock(lock) {
      if cancelled then ()
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
    private def hold(event: StreamEvent.Live): Unit =
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
            reportPendingGap(reason).foreach(ending =>
              call(() => listener(StreamEvent.Disconnected(lastDeliveredSeq, ending))): Unit
            )
          case End.Cancelled => ()
      }

    /**
     * A lagging subscriber can end with live events dropped since its last gap marker - the durable
     * event that did not fit would have carried them. They are reported as a
     * [[StreamEvent.LiveGap]] just before `Disconnected`, so the count is never lost. Returns the
     * reason the final `Disconnected` carries: `reason`, or [[DisconnectReason.ListenerFailed]] if
     * the listener throws on that gap, as on any other event; `None` - no `Disconnected` - if the
     * subscription was cancelled meanwhile.
     */
    private def reportPendingGap(reason: DisconnectReason): Option[DisconnectReason] =
      val dropped = withLock(lock) {
        val pending = if reason == DisconnectReason.Lagging || failed.isDefined then droppedLive else 0
        droppedLive = 0
        pending
      }
      if dropped == 0 then Some(reason)
      else
        deliver(StreamEvent.LiveGap(dropped)) match
          case Right(_)                      => Some(reason)
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
     * Under `hubLock`, queues whatever was handed over since the last read - reading on while pages
     * come back full - with the live events held among it, then the rest of the held events, and
     * switches to queueing what the hub hands over. An event read but not yet handed over
     * ([[handedOver]]) ends the catch-up: commits hand over under `hubLock`, so it is handed to the
     * dispatcher once it is in the live set, behind the held events. Live events dropped while
     * replaying and not yet held as a gap marker were dropped after every held event, so they are
     * reported after all of these, not with the first event caught up. A short store read under the
     * lock; no listener call.
     */
    private def switchToLive(): Either[End, Unit] =
      withLock(hubLock) {
        val droppedWhileReplaying = withLock(lock) {
          val dropped = droppedLive
          droppedLive = 0
          dropped
        }
        @tailrec def catchUp(): Either[End, Unit] =
          read() match
            case Left(stop) => Left(stop)
            case Right(page) =>
              val caughtUp = page.takeWhile(record => handedOver(record.seq))
              withLock(lock) {
                caughtUp.foreach { record =>
                  if record.seq > lastQueuedSeq then queueHeld(takeHeldBefore(record.seq))
                  queueDurable(record)
                }
              }
              if page.size < PageSize || caughtUp.size < page.size || withLock(lock)(lagging) then Right(())
              else catchUp()
        catchUp().map { _ =>
          // live events held past the last commit, then barriers of runs that ended during the
          // replay, go behind everything caught up
          withLock(lock) {
            queueHeld(held.map(_.event))
            held = Vector.empty
            droppedLive += droppedWhileReplaying
            joined = true
            pendingEnds.foreach(queueEnd)
            pendingEnds = Vector.empty
          }
        }
      }

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
              .toRight(End.Disconnect(failed.fold(DisconnectReason.Lagging)(DisconnectReason.ReplayFailed(_))))
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

    /** Calls the listener; a throw ends the subscription, and a delivered durable event advances `lastDeliveredSeq`. */
    private def deliver(event: StreamEvent): Either[End, Unit] =
      outcome(call(() => listener(event))).map { _ =>
        event match
          case StreamEvent.Durable(record) => lastDeliveredSeq = record.seq
          case _                           => ()
      }

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
          // an Observer's dispatcher, before its run's claim is handed to it
          TimeUnit.NANOSECONDS.sleep(watch.pollInterval.toNanos)
          watchLoop()
        case Some(Some(after)) =>
          Try(checkpointer.awaitEventsAfter(threadId, after, PageSize, watch.pollInterval)).toResult.flatten match
            case _ if cancelled || finished => ()
            case Left(_: CancelledError)    => ()
            case Left(error) =>
              withLock(lock)(fail(error))
            case Right(found) =>
              val stuck = found.nonEmpty && withLock(commitLock)(withLock(hubLock)(withLock(lock)(queueWatched(found))))
              // what did not fit stays in the store until the listener has caught up; read it again later
              if stuck then TimeUnit.NANOSECONDS.sleep(watch.pollInterval.toNanos)
              watchLoop()

    /**
     * Queues, in order, the events the watch read that follow the last queued, while they fit: the
     * durable slots are never overfilled, so the watch never makes a subscriber lag. Returns whether
     * the watch should wait before reading again: it stopped for want of room, or at an event that
     * does not follow the last queued, which a store never returns but which must not make the watch
     * spin. Holding `commitLock`, `hubLock` and `lock`.
     */
    private def queueWatched(found: Vector[EventRecord]): Boolean =
      @tailrec def next(rest: List[EventRecord]): Boolean = rest match
        case Nil                                           => false
        case _ if cancelled || halted                      => false
        case record :: more if record.seq <= lastQueuedSeq => next(more)
        case record :: _ if record.seq > lastQueuedSeq + 1 => true
        case _ if durableQueued >= capacity                => true
        case record :: more =>
          queueDurable(record)
          next(more)
      next(found.toList)

    /** The next page after `lastQueuedSeq`; a failed or interrupted read ends the subscription. */
    private def read(): Either[End, Vector[EventRecord]] =
      val from = withLock(lock)(lastQueuedSeq)
      CancelledError.catchInterrupt(
        Try(checkpointer.eventsAfter(threadId, from, PageSize)).toResult.flatMap(identity)
      ) match
        case _ if cancelled     => Left(End.Cancelled)
        case Right(Right(page)) => Right(page)
        case Right(Left(error)) => Left(End.Disconnect(DisconnectReason.ReplayFailed(error)))
        case Left(_) =>
          Left(End.Disconnect(DisconnectReason.ReplayFailed(CancelledError(s"replay of thread ${threadId.value}"))))
