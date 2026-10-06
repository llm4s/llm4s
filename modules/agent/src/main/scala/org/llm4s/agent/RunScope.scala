package org.llm4s.agent

import org.llm4s.agent.graph.*

import java.util.concurrent.{ CompletableFuture, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.concurrent.duration.*
import scala.util.Try

/**
 * One run's view of its thread's subscription: passes on the run's durable and live events, every
 * `LiveGap` and a `Disconnected`, and nothing of another run.
 *
 * The scope ends - once - at the first of:
 *  - the run's terminal durable event (`RunCompleted`, `RunSuspended`, `RunFailed`, `RunCancelled`,
 *    `RunTimedOut`), after passing it on;
 *  - a `Disconnected` (the listener fell behind or threw, or - for a subscription that replays -
 *    reading the thread's log failed: `ReplayFailed`), after passing it on;
 *  - the run's end-of-run barrier ([[runEnded]]), which the subscription's dispatcher reaches once
 *    it has delivered everything of the run: the run's result is set only after every event of it
 *    was queued, and the barrier is queued behind them then - or, for a subscription still
 *    replaying, behind everything its switch to live catches up. It ends a run that committed no
 *    terminal event (a crash, or a failed terminal commit) as soon as its last event is delivered;
 *    after a terminal event or a `Disconnected` the scope has already ended and it does nothing;
 *  - [[cancel]], when the caller cancels the subscription it was handed.
 *
 * Ending calls `onEnd` and cancels the subscription it is attached to - from the listener's thread
 * when the end is an event or the barrier, so the cancel neither waits nor interrupts. From then on
 * it passes on nothing, so events the subscription delivers before the cancel takes effect - a
 * later run's, a gap - never reach `listener`. A barrier names its run, and a scope ends only at
 * its own run's.
 */
final private[agent] class RunScope(runId: RunId, listener: StreamEvent => Unit, onEnd: () => Unit = () => ())
    extends RunListener:
  private val subscription = new AtomicReference[Option[Subscription]](None)
  private val ending       = new AtomicBoolean(false)
  // set only after `onEnd` returns, so an `attach` racing the end never interrupts `onEnd`
  private val ended    = new AtomicBoolean(false)
  private val finished = new CompletableFuture[Unit]()
  // the thread the listener is called on, once it has been: `awaitEnd` from the listener cannot wait for itself
  @volatile private var listenerThread: Option[Thread] = None

  def apply(event: StreamEvent): Unit =
    if !ending.get then
      event match
        case StreamEvent.Durable(record) if record.runId == runId.value =>
          deliver(event)
          if RunScope.terminal(record.event) then end()
        case StreamEvent.Durable(_)                              => ()
        case live: StreamEvent.Live if live.runId == runId.value => deliver(event)
        case _: StreamEvent.Live                                 => ()
        case disconnected: StreamEvent.Disconnected              =>
          // the subscription is over whatever the listener does with its last event
          val delivered = Try(deliver(disconnected))
          end()
          delivered.get
        case gap: StreamEvent.LiveGap => deliver(gap)

  private def deliver(event: StreamEvent): Unit =
    listenerThread = Some(Thread.currentThread())
    listener(event)

  /**
   * The dispatcher reached run `id`'s end-of-run barrier: everything of the run queued to this
   * scope has been delivered. Ends the scope, from the listener's thread, if `id` is its run;
   * another run's barrier is ignored.
   */
  def runEnded(id: RunId): Unit =
    if id == runId && !ending.get then
      listenerThread = Some(Thread.currentThread())
      end()

  /** Attaches the subscription to cancel at the end; cancels it at once if the scope has already ended. */
  def attach(s: Subscription): Unit =
    subscription.set(Some(s))
    if ended.get then s.cancel()

  /**
   * Ends for a caller cancelling the subscription it was handed: if the scope has not begun to end,
   * cancels the subscription - off the listener, so once this returns no listener call is running
   * and none will start - then calls `onEnd`. If an end has begun, waits for it to complete (it
   * cancels the subscription before completing) rather than cancelling during its `onEnd` - except
   * on the listener's own thread, which returns at once, as the kernel subscription's cancel does
   * there: the end in progress may itself be waiting for the listener call to return. Ends once
   * only; a cancel after the end has completed returns at once.
   */
  def cancel(): Unit =
    if !endCancelling() && !listenerThread.exists(_ eq Thread.currentThread()) then finished.join(): Unit

  /** Whether the scope has ended. */
  def isEnded: Boolean = ending.get

  /**
   * Waits up to `timeout` for the scope to end: `Right(true)` once it has - after the listener
   * returned from the run's terminal event or a `Disconnected`, or at the run's end-of-run barrier - and
   * `Right(false)` if it is still open at the bound, or at once when called from the listener
   * itself, which cannot wait for its own return. `Left` if the waiting thread is interrupted; the
   * interrupt flag is then clear.
   */
  def awaitEnd(timeout: FiniteDuration): Either[InterruptedException, Boolean] =
    if finished.isDone then Right(true)
    else if listenerThread.exists(_ eq Thread.currentThread()) then Right(false)
    else
      org.llm4s.error.CancelledError
        .catchInterrupt(Try(finished.get(math.max(0L, timeout.toMillis), TimeUnit.MILLISECONDS)))
        .map(_ => finished.isDone)

  /** Ends from the listener's thread: `onEnd`, then the cancel, which from there returns at once. */
  private def end(): Unit =
    if ending.compareAndSet(false, true) then
      onEnd()
      ended.set(true)
      subscription.get.foreach(_.cancel())
      finished.complete(()): Unit

  /**
   * Ends - unless an end has begun - cancelling first, so no listener call follows, then calling
   * `onEnd`: the end of [[cancel]]. Whether this call ended the scope.
   */
  private def endCancelling(): Boolean =
    val won = ending.compareAndSet(false, true)
    if won then
      subscription.get.foreach(_.cancel())
      onEnd()
      ended.set(true)
      finished.complete(()): Unit
    won

private[agent] object RunScope:

  /** Whether `event` is the last durable event of its run. */
  def terminal(event: RunEvent): Boolean = event match
    case _: RunEvent.RunSuspended | _: RunEvent.RunFailed                     => true
    case RunEvent.RunCompleted | RunEvent.RunCancelled | RunEvent.RunTimedOut => true
    case _                                                                    => false
