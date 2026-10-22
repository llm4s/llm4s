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
 *  - durable events are queued, up to `durableCapacity` waiting to be taken - by default
 *    [[Agent.StreamCapacity]], the room the kernel gives a listener's durable queue. One more ends
 *    the hand-over as the kernel's `Disconnected(lastSeq, Lagging)` would: `take` returns what is
 *    queued, then a `Left`. Only a consumer that has
 *    stopped draining reaches it, and the buffer never holds more than that;
 *  - a live event - or a kernel `LiveGap` - that arrives while `capacity` live events are queued is
 *    dropped, and the consumer receives one `StreamEvent.LiveGap(dropped)` at the position of the
 *    drops, before the next event queued after them (or before the end), as the kernel reports its
 *    own drops.
 *
 * `take` returns the next event; `Right(None)` after the run's terminal event, after [[end]] once
 * everything queued before it has been taken, or after [[close]]; or `Left` when the subscription
 * was disconnected (fell behind, or failed), once everything queued before that has been taken -
 * after a [[close]] too. A `Left` names the `seq` of the last durable event `take` returned, the
 * consumer's position when the error is reported (not the subscription's, which may be ahead of it
 * by what was queued, or by what a `close` dropped): resubscribing with `afterSeq` set to it
 * repeats nothing the consumer took and skips nothing it did not.
 */
final private[llm4s] class AgentEventBuffer(capacity: Int, durableCapacity: Int = Agent.StreamCapacity):
  private val lock     = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val queue    = new java.util.ArrayDeque[StreamEvent]()
  // live events (and gap markers) in `queue`, and live events dropped since the last marker
  private var liveQueued = 0
  private var dropped    = 0
  // durable events in `queue`, and the `seq` of the last one taken
  private var durableQueued = 0
  private var lastTaken     = 0L
  // no more events will be accepted: a terminal event was taken, a disconnect arrived, or `close`
  private var finished = false
  // the run's scope has ended: nothing more will arrive, but what is queued is still taken
  private var ended = false
  // why the hand-over failed, given the consumer's position when `take` reports it
  private var failure: Option[Long => LLMError] = None

  /** The run-scoped listener: queues `event`, or counts it as dropped; never blocks. */
  val listener: StreamEvent => Unit = event =>
    withLock(lock) {
      if !finished then
        event match
          case StreamEvent.Disconnected(_, reason) =>
            failure =
              Some(taken => ValidationError("events", s"the event subscription ended ($reason) after seq $taken"))
            finished = true
          case _: StreamEvent.Durable if durableQueued >= durableCapacity =>
            failure = Some(taken =>
              ValidationError(
                "events",
                s"the consumer fell more than $durableCapacity durable events behind, after seq $taken"
              )
            )
            finished = true
          case durable: StreamEvent.Durable =>
            flushGap()
            queue.add(durable)
            durableQueued += 1
          case StreamEvent.LiveGap(n) if liveQueued >= capacity                     => dropped += n
          case _: (StreamEvent.Live | StreamEvent.Nested) if liveQueued >= capacity => dropped += 1
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
            case StreamEvent.Durable(r) =>
              durableQueued -= 1
              lastTaken = r.seq
              if RunScope.terminal(r.event) then finished = true
            case _ => liveQueued -= 1
          Right(Some(event))
        // drops after the last queued event: reported before the end
        case None if dropped > 0 =>
          val gap = StreamEvent.LiveGap(dropped)
          dropped = 0
          Right(Some(gap))
        // the error names the last durable `seq` the consumer took, read now: everything queued
        // before the failure has been taken, so it is where a resubscription continues from
        case None if finished || ended => failure.map(_(lastTaken)).toLeft(None)
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
    durableQueued = 0
    dropped = 0
    notEmpty.signalAll()
  }
