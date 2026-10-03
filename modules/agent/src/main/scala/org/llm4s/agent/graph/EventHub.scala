package org.llm4s.agent.graph

import org.llm4s.error.CancelledError
import org.llm4s.types.{ Result, TryOps }

import java.util.concurrent.locks.{ Condition, ReentrantLock }
import scala.annotation.tailrec
import scala.util.{ Failure, Success, Try, Using }

/** Runs `body` holding `lock`, releasing it on every exit, an `InterruptedException` included. */
private[graph] def withLock[A](lock: ReentrantLock)(body: => A): A =
  lock.lock()
  Using.resource(new AutoCloseable { def close(): Unit = lock.unlock() })(_ => body)

/**
 * Delivers a runtime's events to subscribers. Each subscription has its own dispatcher: a bounded
 * queue drained in order by one virtual thread, the only thread its listener is called on. The
 * commit path hands events to every queue and returns without waiting on a listener.
 *
 * Durable events reach each subscriber in ascending `seq`, at most once, and only after the commit
 * that numbered them. A durable event that does not fit disconnects the subscriber as lagging once
 * what is already queued has been delivered. A live event is accepted only while two slots are
 * free, so a [[StreamEvent.LiveGap]] counting the live events dropped before it always fits.
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
  ): Result[Subscription] =
    val dispatcher = new Dispatcher(threadId, afterSeq, capacity, listener)
    dispatcher.start()
    Right(dispatcher)

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

  /**
   * One subscriber. It replays the log directly to the listener, then, under `hubLock`, reads what
   * was committed since and joins the live set - commits hand events over under the same lock, so
   * none lands between the last read and joining, and the `seq` check drops any already read.
   * After that it drains its queue. The listener is never called while a lock is held.
   */
  final private class Dispatcher(
    threadId: ThreadId,
    afterSeq: Long,
    capacity: Int,
    listener: StreamEvent => Unit
  ) extends Subscription:
    private val lock                = new ReentrantLock()
    private val notEmpty: Condition = lock.newCondition()

    // guarded by `lock`; `lastQueuedSeq` is also advanced by replay, before the dispatcher joins
    private val queue               = new java.util.ArrayDeque[StreamEvent]()
    private var lastQueuedSeq       = afterSeq
    private var droppedLive         = 0
    private var lagging             = false
    @volatile private var cancelled = false

    /** The highest durable `seq` the listener returned from; written only by the dispatcher thread. */
    @volatile private var lastDeliveredSeq = afterSeq

    /** Set by `start`, before `subscribe` returns the subscription that can cancel it. */
    @volatile private var thread: Option[Thread] = None

    def start(): Unit =
      thread = Some(Thread.ofVirtual().name(s"llm4s-subscriber-${threadId.value}").start(() => run()))

    def cancel(): Unit =
      withLock(lock) {
        cancelled = true
        notEmpty.signalAll()
      }
      leave(threadId, this)
      thread.foreach(_.interrupt())

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

    private def run(): Unit =
      // leaves the live set on every exit, including one by a fatal error
      Using.resource(new AutoCloseable { def close(): Unit = leave(threadId, Dispatcher.this) }) { _ =>
        val ending = replay().flatMap(_ => switchToLive()).fold(identity, _ => drain())
        ending match
          case End.Disconnect(reason) if !cancelled =>
            leave(threadId, this)
            CancelledError.catchInterrupt(Try(listener(StreamEvent.Disconnected(lastDeliveredSeq, reason)))): Unit
          case _ => ()
      }

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
        catchUp().map(_ => join(threadId, this))
      }

    /** Takes queued events in order; ends when cancelled, or when lagging and drained. */
    @tailrec private def drain(): End =
      val next: Either[End, Option[StreamEvent]] =
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
        case Right(Some(event)) =>
          deliver(event) match
            case Right(_)   => drain()
            case Left(stop) => stop

    /** Waits, holding `lock`, until there is something to deliver or a reason to stop. */
    @tailrec private def awaitWork(): Unit =
      if !cancelled && !lagging && queue.isEmpty then
        notEmpty.await()
        awaitWork()

    /** Calls the listener; a throw ends the subscription, and a delivered durable event advances `lastDeliveredSeq`. */
    private def deliver(event: StreamEvent): Either[End, Unit] =
      if cancelled then Left(End.Cancelled)
      else
        val outcome = CancelledError.catchInterrupt(Try(listener(event)))
        if cancelled then Left(End.Cancelled)
        else
          outcome match
            case Right(Success(_)) =>
              event match
                case StreamEvent.Durable(record) => lastDeliveredSeq = record.seq
                case _                           => ()
              Right(())
            case Right(Failure(cause)) => Left(End.Disconnect(DisconnectReason.ListenerFailed(cause)))
            case Left(interrupted)     => Left(End.Disconnect(DisconnectReason.ListenerFailed(interrupted)))

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
