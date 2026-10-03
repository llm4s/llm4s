package org.llm4s.agent.graph

import org.llm4s.error.CancelledError
import org.llm4s.types.Result

import java.util.concurrent.CompletableFuture
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
   * `release` runs before the result is set, on every exit.
   */
  private[graph] def launch(
    body: () => RunResult[O],
    crashed: Throwable => RunResult[O],
    release: () => Unit
  ): Unit =
    val thread = Thread.ofVirtual().name(s"llm4s-run-${threadId.value}").unstarted(() => run(body, crashed, release))
    runThread = thread
    thread.start()

  private def run(body: () => RunResult[O], crashed: Throwable => RunResult[O], release: () => Unit): Unit =
    var outcome: Option[RunResult[O]] = None
    // `close` runs on every exit, even a fatal error that nothing below catches
    Using.resource(new AutoCloseable {
      def close(): Unit =
        release()
        result.complete(outcome.getOrElse(crashed(new IllegalStateException("run thread ended abnormally")))): Unit
    }) { _ =>
      outcome = Some(CancelledError.catchInterrupt(allCatch.either(body())) match
        case Right(Right(done))  => done
        case Right(Left(thrown)) => crashed(thrown)
        case Left(interrupted)   => crashed(interrupted)
      )
    }
