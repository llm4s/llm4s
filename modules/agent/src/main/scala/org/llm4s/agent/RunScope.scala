package org.llm4s.agent

import org.llm4s.agent.graph.*

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

/**
 * One run's view of its thread's subscription: passes on the run's durable and live events, every
 * `LiveGap` and a `Disconnected`, and nothing of another run. After the run's terminal durable event
 * it calls `onEnd` and cancels the subscription it is attached to (from the listener, so the cancel
 * neither waits nor interrupts); from then on it passes on nothing, so events the subscription
 * delivers before the cancel takes effect - a later run's, a gap - never reach `listener`.
 */
final private[agent] class RunScope(runId: RunId, listener: StreamEvent => Unit, onEnd: () => Unit = () => ())
    extends (StreamEvent => Unit):
  private val subscription = new AtomicReference[Option[Subscription]](None)
  private val ending       = new AtomicBoolean(false)
  // set only after `onEnd` returns, so an `attach` racing the end never interrupts `onEnd`
  private val ended = new AtomicBoolean(false)

  def apply(event: StreamEvent): Unit =
    if !ending.get then
      event match
        case StreamEvent.Durable(record) if record.runId == runId.value =>
          listener(event)
          if RunScope.terminal(record.event) then end()
        case StreamEvent.Durable(_)                              => ()
        case live: StreamEvent.Live if live.runId == runId.value => listener(event)
        case _: StreamEvent.Live                                 => ()
        case other                                               => listener(other)

  /** Attaches the subscription to cancel at the end; cancels it at once if the run has already ended. */
  def attach(s: Subscription): Unit =
    subscription.set(Some(s))
    if ended.get then s.cancel()

  private def end(): Unit =
    if ending.compareAndSet(false, true) then
      onEnd()
      ended.set(true)
      subscription.get.foreach(_.cancel())

private[agent] object RunScope:

  /** Whether `event` is the last durable event of its run. */
  def terminal(event: RunEvent): Boolean = event match
    case _: RunEvent.RunSuspended | _: RunEvent.RunFailed                     => true
    case RunEvent.RunCompleted | RunEvent.RunCancelled | RunEvent.RunTimedOut => true
    case _                                                                    => false
