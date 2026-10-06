package org.llm4s.agent

import org.llm4s.agent.graph.*

import java.util.concurrent.{ CompletableFuture, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger, AtomicReference }
import scala.concurrent.duration.*
import scala.util.{ Try, Using }

/**
 * One run's view of its thread's subscription: passes on the run's durable and live events, every
 * `LiveGap` and a `Disconnected`, and nothing of another run.
 *
 * The scope ends - once - at the first of:
 *  - the run's terminal durable event (`RunCompleted`, `RunSuspended`, `RunFailed`, `RunCancelled`,
 *    `RunTimedOut`), after passing it on;
 *  - a `Disconnected` (the listener fell behind or threw, or - for a subscription that replays -
 *    reading the thread's log failed: `ReplayFailed`), after passing it on;
 *  - [[closeWhenQuiet]], for a run that ended without committing a terminal event (a crash, or a
 *    failed terminal commit), once the subscription has delivered everything it had;
 *  - [[cancel]], when the caller cancels the subscription it was handed.
 *
 * Ending calls `onEnd` and cancels the subscription it is attached to - from the listener when the
 * end is an event, so the cancel neither waits nor interrupts. From then on it passes on nothing, so
 * events the subscription delivers before the cancel takes effect - a later run's, a gap - never
 * reach `listener`.
 */
final private[agent] class RunScope(runId: RunId, listener: StreamEvent => Unit, onEnd: () => Unit = () => ())
    extends (StreamEvent => Unit):
  private val subscription = new AtomicReference[Option[Subscription]](None)
  private val ending       = new AtomicBoolean(false)
  // set only after `onEnd` returns, so an `attach` racing the end never interrupts `onEnd`
  private val ended    = new AtomicBoolean(false)
  private val finished = new CompletableFuture[Unit]()
  // listener calls in progress, and when the last one began or returned: what `closeWhenQuiet` waits on
  private val calls                  = new AtomicInteger(0)
  @volatile private var lastActivity = System.nanoTime()
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
    calls.incrementAndGet()
    lastActivity = System.nanoTime()
    Using.resource(new AutoCloseable {
      def close(): Unit =
        lastActivity = System.nanoTime()
        calls.decrementAndGet(): Unit
    })(_ => listener(event))

  /** Attaches the subscription to cancel at the end; cancels it at once if the scope has already ended. */
  def attach(s: Subscription): Unit =
    subscription.set(Some(s))
    if ended.get then s.cancel()

  /**
   * Ends for a caller cancelling the subscription it was handed: if the scope has not begun to end,
   * cancels the subscription - off the listener, so once this returns no listener call is running
   * and none will start - then calls `onEnd`. If it has, waits for that end, which cancels the
   * subscription before it completes, rather than cancelling it during `onEnd`. Once only, as every
   * end is; a second cancel returns at once.
   */
  def cancel(): Unit =
    if !endCancelling() then finished.join(): Unit

  /** Whether the scope has ended. */
  def isEnded: Boolean = ending.get

  /**
   * Waits up to `timeout` for the scope to end: `Right(true)` once it has - after the listener
   * returned from the run's terminal event or a `Disconnected`, or after a quiet close - and
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

  /** Ends from the listener: `onEnd`, then the cancel, which from the listener returns at once. */
  private def end(): Unit =
    if ending.compareAndSet(false, true) then
      onEnd()
      ended.set(true)
      subscription.get.foreach(_.cancel())
      finished.complete(()): Unit

  /**
   * Blocks until the scope ends, or until no listener call has been in progress for `quiet` -
   * counted from this call at the earliest - and in
   * the second case ends it: cancels the subscription first - so no listener call follows - then
   * calls `onEnd`. For a run that has ended without a terminal event: every event of it was queued
   * to the subscription before the run's result was set, so a subscription idle for `quiet` after
   * that has nothing left of the run to deliver. Called off the listener's thread.
   */
  def closeWhenQuiet(quiet: FiniteDuration): Unit =
    // the quiet clock starts no earlier than this call - after the run's result is set, so after every
    // event of it was queued - or a scope idle since before then would end before taking them
    val since = System.nanoTime()
    var done  = false
    while !done do
      val idleFor = (System.nanoTime() - math.max(lastActivity, since)).nanos
      if finished.isDone then done = true
      else if calls.get == 0 && idleFor >= quiet then
        endCancelling(): Unit
        done = true
      else
        val wait = if calls.get == 0 then quiet - idleFor else quiet
        Try(finished.get(math.max(1L, wait.toMillis), TimeUnit.MILLISECONDS)): Unit

  /**
   * Ends - unless an end has begun - cancelling first, so no listener call follows, then calling
   * `onEnd`: the end of [[closeWhenQuiet]] and [[cancel]]. Whether this call ended the scope.
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

  /**
   * How long a scope whose run ended without a terminal event waits for its subscription to go
   * idle before ending itself. A heuristic, not a barrier;
   * [[https://github.com/llm4s/llm4s/issues/1378 #1378]] replaces it with a deterministic one.
   */
  val Quiet: FiniteDuration = 1.second

  /** Whether `event` is the last durable event of its run. */
  def terminal(event: RunEvent): Boolean = event match
    case _: RunEvent.RunSuspended | _: RunEvent.RunFailed                     => true
    case RunEvent.RunCompleted | RunEvent.RunCancelled | RunEvent.RunTimedOut => true
    case _                                                                    => false

  /**
   * Whether a run that ended with `error` may have committed no terminal event: it crashed
   * ([[GraphError.RunCrashed]]), or a commit failed ([[GraphError.CheckpointWriteFailed]]), possibly
   * its terminal one.
   */
  def mayLackTerminal(error: org.llm4s.error.LLMError): Boolean = error match
    case _: GraphError.RunCrashed | _: GraphError.CheckpointWriteFailed => true
    case _                                                              => false

  /**
   * Ends `scope` if `handle`'s run ends without a terminal event: a virtual thread waits for the
   * run's result and, when it is such a failure, runs [[RunScope.closeWhenQuiet]]. Otherwise the
   * run's terminal event ends the scope, and the thread exits as soon as the run does.
   */
  def watch(handle: RunHandle[?], scope: RunScope): Unit =
    Thread
      .ofVirtual()
      .name(s"llm4s-run-scope-${handle.threadId.value}")
      .start { () =>
        handle.await() match
          case Right(RunResult.Failed(_, error)) if mayLackTerminal(error) => scope.closeWhenQuiet(Quiet)
          case _                                                           => ()
      }: Unit
