package org.llm4s.agent.graph

import org.llm4s.error.CancelledError
import org.llm4s.types.{ Result, TryOps }

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
   * the run's result is set, so after every event of the run was handed to the hub. A no-op unless
   * the listener is a [[RunListener]], and once the subscription is cancelled or lagging: a lagging
   * subscription ends with its `Disconnected` instead, so a scope never ends as if it had seen
   * everything when it has not.
   */
  def endOfRun(runId: RunId): Unit

/**
 * Delivers a runtime's events to subscribers. Each subscription has its own dispatcher: a bounded
 * queue drained in order by one virtual thread, the only thread its listener is called on. The
 * commit path hands events to every queue and returns without waiting on a listener.
 *
 * A run-scoped subscription ([[RunListener]]) also gets its run's end-of-run barrier
 * ([[Dispatched.endOfRun]]) once the run's result is set: a marker queued behind the run's last
 * event that is never passed to the listener as an event. Reaching it, the dispatcher calls
 * [[RunListener.runEnded]], which ends a scope whose run committed no terminal event. The barrier
 * may take one slot beyond `capacity` (two with a pending gap): it is never dropped, and never
 * makes a subscriber lag.
 *
 * Durable events reach each subscriber in ascending `seq`, at most once, and only after the commit
 * that numbered them. A durable event that does not fit disconnects the subscriber as lagging once
 * what is already queued has been delivered. A live event is accepted only while two slots are
 * free, so a [[StreamEvent.LiveGap]] counting the live events dropped before it always fits. A
 * lagging subscriber with dropped live events still pending gets that `LiveGap` after its queue
 * drains and just before `Disconnected(lastSeq, Lagging)`.
 */
final private[graph] class EventHub(checkpointer: Checkpointer):

  private val PageSize = 500

  /** Guards `subscribers`; commits hand events over under it, so each queue sees commit order. */
  private val hubLock = new ReentrantLock()

  /** Dispatchers past replay, by thread; guarded by `hubLock`. */
  private var subscribers = Map.empty[String, Vector[Dispatcher]]

  /** Starts a dispatcher that replays events with `seq > afterSeq`, then delivers new ones. */
  def subscribe(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit
  ): Result[Dispatched] =
    val dispatcher = new Dispatcher(threadId, afterSeq, capacity, listener)
    dispatcher.start()
    Right(dispatcher)

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

  /** Offers committed `records` to the thread's subscribers; never blocks on a listener. */
  def durable(threadId: ThreadId, records: Vector[EventRecord]): Unit =
    if records.nonEmpty then
      withLock(hubLock) {
        subscribers.getOrElse(threadId.value, Vector.empty).foreach(d => records.foreach(d.offerDurable))
      }

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
   * One subscriber. It replays the log directly to the listener, then, under `hubLock`, reads what
   * was committed since and joins the live set - commits hand events over under the same lock, so
   * none lands between the last read and joining, and the `seq` check drops any already read.
   * After that it drains its queue. The listener is never called while a lock is held. A
   * `preJoined` dispatcher ([[observe]]) joined the live set before it started, and only drains.
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
    private val queue               = new java.util.ArrayDeque[StreamEvent | RunEnd]()
    private var lastQueuedSeq       = afterSeq
    private var droppedLive         = 0
    private var lagging             = false
    @volatile private var cancelled = false

    /** Whether the dispatcher has joined the live set; until then a barrier waits in `pendingEnds`. */
    private var joined      = preJoined
    private var pendingEnds = Vector.empty[RunId]

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

    def offerDurable(record: EventRecord): Unit = withLock(lock) {
      if !cancelled && !lagging && record.seq > lastQueuedSeq then
        val needed = if droppedLive > 0 then 2 else 1
        if capacity - queue.size < needed then lagging = true
        else
          flushGap()
          queue.add(StreamEvent.Durable(record))
          lastQueuedSeq = record.seq
        notEmpty.signal()
    }

    def offerLive(event: StreamEvent.Live): Unit = withLock(lock) {
      if !cancelled && !lagging then
        // the gap marker needs one slot and the event one
        if capacity - queue.size >= 2 then
          flushGap()
          queue.add(event)
        else droppedLive += 1
        notEmpty.signal()
    }

    private def flushGap(): Unit =
      if droppedLive > 0 then
        queue.add(StreamEvent.LiveGap(droppedLive))
        droppedLive = 0

    def endOfRun(runId: RunId): Unit =
      listener match
        case _: RunListener =>
          withLock(lock) {
            if joined then queueEnd(runId) else pendingEnds = pendingEnds :+ runId
          }
        case _ => ()

    /**
     * Queues `runId`'s barrier, holding `lock`, unless cancelled or lagging; a pending gap goes
     * first, so the listener learns of live events of the run dropped before it. Exempt from
     * `capacity`.
     */
    private def queueEnd(runId: RunId): Unit =
      if !cancelled && !lagging then
        flushGap()
        queue.add(RunEnd(runId))
        notEmpty.signal()

    private def run(): Unit =
      // leaves the live set on every exit, including one by a fatal error
      Using.resource(new AutoCloseable { def close(): Unit = leave(threadId, Dispatcher.this) }) { _ =>
        val ready  = if preJoined then Right(()) else replay().flatMap(_ => switchToLive())
        val ending = ready.fold(identity, _ => drain())
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
     * event that did not fit needed a slot for that marker too. They are reported as a
     * [[StreamEvent.LiveGap]] just before `Disconnected`, so the count is never lost. Returns the
     * reason the final `Disconnected` carries: `reason`, or [[DisconnectReason.ListenerFailed]] if
     * the listener throws on that gap, as on any other event; `None` - no `Disconnected` - if the
     * subscription was cancelled meanwhile.
     */
    private def reportPendingGap(reason: DisconnectReason): Option[DisconnectReason] =
      val dropped = withLock(lock) {
        val pending = if reason == DisconnectReason.Lagging then droppedLive else 0
        droppedLive = 0
        pending
      }
      if dropped == 0 then Some(reason)
      else
        deliver(StreamEvent.LiveGap(dropped)) match
          case Right(_)                      => Some(reason)
          case Left(End.Disconnect(failure)) => Some(failure)
          case Left(End.Cancelled)           => None

    /** Delivers committed events directly until a read finds none. */
    @tailrec private def replay(): Either[End, Unit] =
      read() match
        case Left(stop)                  => Left(stop)
        case Right(page) if page.isEmpty => Right(())
        case Right(page) =>
          page.foldLeft[Either[End, Unit]](Right(()))((done, record) =>
            done.flatMap { _ =>
              if record.seq <= lastQueuedSeq then Right(())
              else deliver(StreamEvent.Durable(record)).map(_ => withLock(lock) { lastQueuedSeq = record.seq })
            }
          ) match
            case Right(_) => replay()
            case left     => left

    /**
     * Under `hubLock`, queues whatever was committed since the last read - reading on while pages
     * come back full - then joins the live set. A short store read under the lock; no listener call.
     */
    private def switchToLive(): Either[End, Unit] =
      withLock(hubLock) {
        @tailrec def catchUp(): Either[End, Unit] =
          read() match
            case Left(stop) => Left(stop)
            case Right(page) =>
              page.foreach(offerDurable)
              if page.size < PageSize || withLock(lock)(lagging) then Right(()) else catchUp()
        catchUp().map { _ =>
          join(threadId, this)
          // barriers of runs that ended during the replay go behind everything caught up
          withLock(lock) {
            joined = true
            pendingEnds.foreach(queueEnd)
            pendingEnds = Vector.empty
          }
        }
      }

    /** Takes queued events in order; ends when cancelled, or when lagging and drained. */
    @tailrec private def drain(): End =
      val next: Either[End, Option[StreamEvent | RunEnd]] =
        CancelledError.catchInterrupt(withLock(lock) {
          awaitWork()
          if cancelled then Left(End.Cancelled)
          else Option(queue.poll()).map(Some(_)).toRight(End.Disconnect(DisconnectReason.Lagging))
        }) match
          case Right(taken) => taken
          // only `cancel` interrupts this thread; anything else is ignored
          case Left(_) => if cancelled then Left(End.Cancelled) else Right(None)
      next match
        case Left(stop)  => stop
        case Right(None) => drain()
        case Right(Some(item)) =>
          val delivered = item match
            case RunEnd(runId)      => reachEnd(runId)
            case event: StreamEvent => deliver(event)
          delivered match
            case Right(_)   => drain()
            case Left(stop) => stop

    /** At `runId`'s barrier: tells a [[RunListener]], as a listener call, that its run has ended. */
    private def reachEnd(runId: RunId): Either[End, Unit] =
      listener match
        case run: RunListener => outcome(call(() => run.runEnded(runId)))
        case _                => Right(())

    /** Waits, holding `lock`, until there is something to deliver or a reason to stop. */
    @tailrec private def awaitWork(): Unit =
      if !cancelled && !lagging && queue.isEmpty then
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
