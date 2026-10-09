package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.error.{ LLMError, ValidationError }

import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec

/**
 * A hand-over from a run-scoped listener, called on a subscription's dispatcher thread, to one
 * consumer - the bridge under the fs2 and ZIO streams. The listener never blocks, so a slow consumer
 * never backs up the subscription, which would disconnect it as lagging and cancel the run:
 *
 *  - durable events are always queued;
 *  - a live event - or a kernel `LiveGap` - that arrives while `capacity` live events are queued is
 *    dropped, and the consumer receives one `StreamEvent.LiveGap(dropped)` at the position of the
 *    drops, before the next event queued after them (or before the end), as the kernel reports its
 *    own drops.
 *
 * `take` returns the next event; `Right(None)` after the run's terminal event, after [[end]] once
 * everything queued before it has been taken, or after [[close]]; or `Left` when the subscription
 * was disconnected (fell behind, or failed), once everything queued before that has been taken -
 * after a [[close]] too.
 */
final private[llm4s] class AgentEventBuffer(capacity: Int):
  private val lock     = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val queue    = new java.util.ArrayDeque[StreamEvent]()
  // live events (and gap markers) in `queue`, and live events dropped since the last marker
  private var liveQueued = 0
  private var dropped    = 0
  // no more events will be accepted: a terminal event was taken, a disconnect arrived, or `close`
  private var finished = false
  // the run's scope has ended: nothing more will arrive, but what is queued is still taken
  private var ended                     = false
  private var failure: Option[LLMError] = None

  /** The run-scoped listener: queues `event`, or counts it as dropped; never blocks. */
  val listener: StreamEvent => Unit = event =>
    withLock(lock) {
      if !finished then
        event match
          case StreamEvent.Disconnected(lastSeq, reason) =>
            failure = Some(ValidationError("events", s"the event subscription ended after seq $lastSeq: $reason"))
            finished = true
          case durable: StreamEvent.Durable =>
            flushGap()
            queue.add(durable): Unit
          case StreamEvent.LiveGap(n) if liveQueued >= capacity => dropped += n
          case _: StreamEvent.Live if liveQueued >= capacity    => dropped += 1
          case live =>
            flushGap()
            queueLive(live)
        notEmpty.signalAll()
    }

  /** Queues the marker for the live events dropped since the last one; the caller holds `lock`. */
  private def flushGap(): Unit =
    if dropped > 0 then
      queueLive(StreamEvent.LiveGap(dropped))
      dropped = 0

  private def queueLive(event: StreamEvent): Unit =
    queue.add(event)
    liveQueued += 1

  /** Blocks for the next event; see the class description. Interruptible. */
  def take(): Either[LLMError, Option[StreamEvent]] = withLock(lock) {
    @tailrec def next(): Either[LLMError, Option[StreamEvent]] =
      Option(queue.poll()) match
        case Some(event) =>
          event match
            case StreamEvent.Durable(r) if RunScope.terminal(r.event) => finished = true
            case _: StreamEvent.Durable                               => ()
            case _                                                    => liveQueued -= 1
          Right(Some(event))
        // drops after the last queued event: reported before the end
        case None if dropped > 0 =>
          val gap = StreamEvent.LiveGap(dropped)
          dropped = 0
          Right(Some(gap))
        case None if finished || ended => failure.toLeft(None)
        case None =>
          notEmpty.await()
          next()
    next()
  }

  /**
   * The run's scope has ended - for a run that ended without a terminal event, the only signal there
   * is. `take` returns what was queued before, then `Right(None)`.
   */
  def end(): Unit = withLock(lock) {
    ended = true
    notEmpty.signalAll()
  }

  /** Stops the hand-over: drops what is queued and wakes a blocked `take`. */
  def close(): Unit = withLock(lock) {
    finished = true
    queue.clear()
    liveQueued = 0
    dropped = 0
    notEmpty.signalAll()
  }
