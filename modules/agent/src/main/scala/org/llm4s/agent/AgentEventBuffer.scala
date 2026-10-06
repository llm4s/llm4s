package org.llm4s.agent

import org.llm4s.agent.graph.*
import org.llm4s.error.{ LLMError, ValidationError }

import java.util.concurrent.locks.ReentrantLock
import scala.annotation.tailrec

/**
 * A bounded hand-over from a run-scoped listener, called on a subscription's dispatcher thread, to
 * one consumer - the bridge under the fs2 and ZIO streams. The listener blocks while the buffer is
 * full, so a slow consumer backs up only its own subscription, which the runtime then disconnects
 * as lagging.
 *
 * `take` returns the next event; `Right(None)` after the run's terminal event, after [[end]] once
 * everything queued before it has been taken, or after [[close]]; or `Left` when the subscription
 * was disconnected (fell behind, or failed), once everything queued before that has been taken.
 */
final private[llm4s] class AgentEventBuffer(capacity: Int):
  private val lock     = new ReentrantLock()
  private val notEmpty = lock.newCondition()
  private val notFull  = lock.newCondition()
  private val queue    = new java.util.ArrayDeque[StreamEvent]()
  // no more events will be accepted: a terminal event was taken, a disconnect arrived, or `close`
  private var finished = false
  // the run's scope has ended: nothing more will arrive, but what is queued is still taken
  private var ended                     = false
  private var failure: Option[LLMError] = None

  /**
   * The run-scoped listener: queues `event`, blocking while the buffer is full. An interrupt while
   * blocked propagates, ending the subscription as `ListenerFailed`.
   */
  val listener: StreamEvent => Unit = event =>
    withLock(lock) {
      while queue.size >= capacity && !finished do notFull.await()
      if !finished then
        event match
          case StreamEvent.Disconnected(lastSeq, reason) =>
            failure = Some(ValidationError("events", s"the event subscription ended after seq $lastSeq: $reason"))
            finished = true
          case _ => queue.add(event): Unit
        notEmpty.signalAll()
    }

  /** Blocks for the next event; see the class description. Interruptible. */
  def take(): Either[LLMError, Option[StreamEvent]] = withLock(lock) {
    @tailrec def next(): Either[LLMError, Option[StreamEvent]] =
      Option(queue.poll()) match
        case Some(event) =>
          notFull.signalAll()
          event match
            case StreamEvent.Durable(r) if RunScope.terminal(r.event) => finished = true
            case _                                                    => ()
          Right(Some(event))
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

  /** Stops the hand-over: drops what is queued, wakes a blocked `take` and a blocked listener. */
  def close(): Unit = withLock(lock) {
    finished = true
    queue.clear()
    notEmpty.signalAll()
    notFull.signalAll()
  }
