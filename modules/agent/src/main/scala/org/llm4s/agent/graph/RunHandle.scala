package org.llm4s.agent.graph

import org.llm4s.error.CancelledError
import org.llm4s.types.Result

import java.util.concurrent.{ CompletableFuture, TimeUnit, TimeoutException }
import java.util.concurrent.atomic.AtomicReference
import scala.util.Using
import scala.util.control.Exception.allCatch

/** Where a run is; see [[RunHandle.status]]. */
enum RunStatus:
  case Running, Completed, Suspended, Failed

/**
 * A run admitted by [[GraphRuntime.start]], [[GraphRuntime.recover]] or [[GraphRuntime.resume]].
 * Its thread was claimed before the handle was returned, and the run executes on a virtual thread
 * owned by the runtime, named `llm4s-run-<threadId>`, which holds the thread until it exits.
 */
trait RunHandle[O]:
  def threadId: ThreadId
  def runId: RunId

  /** Non-blocking: `Running` until the run's result is set, then the result's case. */
  def status: RunStatus

  /**
   * Blocks until the run ends and returns its result; the result is retained, so every call
   * returns the same value. If the awaiting thread is interrupted, returns `Left(CancelledError)`
   * with the interrupt flag still set, and the run continues: only [[cancel]] stops a run.
   */
  def await(): Result[RunResult[O]]

  /**
   * Cancels the run by interrupting its thread; see [[GraphError.Cancelled]]. Returns at once and
   * is idempotent. A no-op once the run has ended, and a run that has already submitted its
   * completed or suspended checkpoint still finishes with that outcome.
   */
  def cancel(): Unit

  /**
   * Subscribes to the run's thread from just before this run's claim event, so it replays this run
   * from its start whenever it is called; see [[GraphRuntime.subscribe]].
   */
  def subscribe(capacity: Int = 1024)(listener: StreamEvent => Unit): Result[Subscription]

/** Why a run was stopped from outside: the first cause recorded wins. */
private[graph] enum StopCause:
  case Cancelled, Expired

/**
 * The runtime's [[RunHandle]]. [[launch]] starts the run thread, which completes `result` on every
 * exit - normal, interrupted, or by an unexpected throwable - after releasing the thread claim, so
 * a caller that has seen the result can start the next run at once.
 */
final private[graph] class DefaultRunHandle[O](
  val threadId: ThreadId,
  val runId: RunId,
  claimSeq: Long,
  private[graph] val cause: AtomicReference[Option[StopCause]],
  subscribeFrom: (Long, Int, StreamEvent => Unit) => Result[Subscription]
) extends RunHandle[O]:

  private val result                      = new CompletableFuture[RunResult[O]]()
  @volatile private var runThread: Thread = null

  def status: RunStatus =
    if !result.isDone then RunStatus.Running
    else
      result.join() match
        case _: RunResult.Completed[?] => RunStatus.Completed
        case _: RunResult.Suspended    => RunStatus.Suspended
        case _: RunResult.Failed       => RunStatus.Failed

  def await(): Result[RunResult[O]] =
    CancelledError.catchInterrupt(result.get()) match
      case Right(value) => Right(value)
      case Left(e) =>
        Thread.currentThread().interrupt()
        Left(CancelledError("await", Some(e)))

  def cancel(): Unit = stop(StopCause.Cancelled)

  /** Records `stopCause` unless a cause is already recorded, and interrupts a live run. */
  private[graph] def stop(stopCause: StopCause): Unit =
    // the thread is started before the handle is returned, and an interrupt on a started thread
    // that has not yet run sets its flag, which the loop checks before every superstep
    if cause.compareAndSet(None, Some(stopCause)) && !result.isDone then runThread.interrupt()

  def subscribe(capacity: Int = 1024)(listener: StreamEvent => Unit): Result[Subscription] =
    subscribeFrom(claimSeq - 1, capacity, listener)

  /**
   * Starts the run thread, running `body`. A throwable escaping it becomes `crashed(throwable)`;
   * `release` runs before the result is set, on every exit. With a `deadline` (a `System.nanoTime`
   * value), it then starts a virtual thread named `llm4s-deadline-<threadId>` that stops the run
   * with [[StopCause.Expired]] when the deadline passes; see [[expireAt]].
   */
  private[graph] def launch(
    body: () => RunResult[O],
    crashed: Throwable => RunResult[O],
    release: () => Unit,
    deadline: Option[Long] = None
  ): Unit =
    val thread = Thread.ofVirtual().name(s"llm4s-run-${threadId.value}").unstarted(() => run(body, crashed, release))
    runThread = thread
    thread.start()
    // armed only once the run thread is started, so `stop` always has a thread to interrupt
    deadline.foreach { at =>
      Thread.ofVirtual().name(s"llm4s-deadline-${threadId.value}").start(() => expireAt(at)): Unit
    }

  /**
   * The deadline thread's body: waits for the run's result until `deadline`, and stops the run if
   * it has not ended by then. The wait ends as soon as the result is set - which the run thread
   * does on every exit - so this thread never outlives the run by more than that, and it holds
   * nothing the run needs, such as the thread claim.
   */
  private def expireAt(deadline: Long): Unit =
    val remaining = math.max(0L, deadline - System.nanoTime())
    DefaultRunHandle.guarded(result.get(remaining, TimeUnit.NANOSECONDS)) match
      case Left(_: TimeoutException) => stop(StopCause.Expired)
      case _                         => ()

  /**
   * The run thread's body. `crashed` must not throw (see [[DefaultRunHandle.guarded]]). `result` is
   * completed on every exit: by `close`, after `release`, even if `release` throws or something
   * escapes [[DefaultRunHandle.guarded]] (a `ControlThrowable`).
   */
  private def run(body: () => RunResult[O], crashed: Throwable => RunResult[O], release: () => Unit): Unit =
    var outcome: Option[RunResult[O]] = None
    Using.resource(new AutoCloseable {
      def close(): Unit =
        DefaultRunHandle.guarded(release()): Unit
        result.complete(outcome.getOrElse(crashed(new IllegalStateException("run thread ended abnormally")))): Unit
    })(_ => outcome = Some(DefaultRunHandle.guarded(body()).fold(crashed, identity)))

private[graph] object DefaultRunHandle:

  /**
   * Runs `body`, returning anything it throws - an `InterruptedException` or a fatal error included -
   * as `Left`; only a `ControlThrowable` propagates. An interrupt's flag is left cleared.
   */
  def guarded[A](body: => A): Either[Throwable, A] =
    CancelledError.catchInterrupt(allCatch.either(body)).fold(Left(_), identity)
