package org.llm4s.agent.graph

import org.llm4s.error.CancelledError
import org.llm4s.types.Result

import java.util.concurrent.{ CompletableFuture, TimeUnit, TimeoutException }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import java.util.concurrent.locks.{ LockSupport, ReentrantLock }
import scala.annotation.tailrec
import scala.concurrent.duration.FiniteDuration
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
   * with the interrupt flag still set, and the run continues: only [[cancel]] stops a run. A run that
   * ends `Failed` with [[GraphError.ClaimLost]] may be recovered once its thread is free; see there.
   */
  def await(): Result[RunResult[O]]

  /**
   * Cancels the run by interrupting its thread; see [[GraphError.Cancelled]]. Returns at once and
   * is idempotent. A no-op once the run has ended, and once it has begun closing: committing its
   * outcome (completed, suspended or failed), stopping on a store failure or a crash, or stopping
   * because it lost its claim ([[GraphError.ClaimLost]]). What it is closing is not interrupted -
   * neither its commits nor the release of its claim - and the run ends with it. A cancel that
   * interrupts a superstep's commit still ends the run `Cancelled`, even if the store reports that
   * commit as failed.
   */
  def cancel(): Unit

  /**
   * Subscribes to the run's thread from just before this run's claim event, so it replays this run
   * from its start whenever it is called; see [[GraphRuntime.subscribe]]. The subscription is the
   * thread's, not the run's: it keeps delivering later runs on the same thread, and holds its parked
   * dispatcher thread until [[Subscription.cancel]] is called. Like every subscription, it
   * delivers live only the commits made through this handle's [[GraphRuntime]].
   */
  def subscribe(capacity: Int = 1024)(listener: StreamEvent => Unit): Result[Subscription]

  /**
   * The subscription of the [[Observer]] this run was admitted with; `None` without one. Like any
   * subscription it is the thread's and keeps delivering later runs: cancel it when done.
   */
  def observation: Option[Subscription]

/**
 * Why a run stops, recorded once: the first cause recorded wins. `Cancelled`, `Expired` and
 * `ClaimLost` are recorded by [[DefaultRunHandle.stop]], which interrupts the run only if its cause
 * was the first; `ClaimLost` comes from the run's claim heartbeat, when the run's lease passed with no
 * renewal landing or a renewal was refused, and the run also records it itself when the store refuses
 * one of its commits as stale. `Finishing` is recorded by the run itself just before it closes on its
 * own account - committing its outcome (its completed or suspended checkpoint, or a failed run's
 * `RunFailed`), or stopping on a store failure or a crash - so a later cancel, expiry or lost claim
 * records nothing and sends no interrupt into its commits or its release; a run that finds another
 * cause already recorded is stopped by that cause instead. A lost claim is acted on even when it is not
 * the first cause: the run's committer is fenced, and its end bounded ([[DefaultRunHandle.endWithin]]).
 */
private[graph] enum StopCause:
  case Cancelled, Expired, ClaimLost, Finishing

/**
 * How a run and its [[DefaultRunHandle]] agree that it stops. `cause` records why (see
 * [[StopCause]]). A stop records its cause and only then interrupts, so the run can see the cause
 * and begin closing between the two; `acknowledged` closes that gap. The run [[acknowledge]]s
 * before it clears its interrupt flag to commit its closing events, and [[interruptUnlessAcknowledged]]
 * interrupts only before that, both holding `lock` - so no stop's interrupt arrives after it.
 */
final private[graph] class StopSignal:
  val cause: AtomicReference[Option[StopCause]] = AtomicReference(None)
  private val lock                              = new ReentrantLock()
  private var acknowledged                      = false

  /** Called by the run before it clears its interrupt flag to close; no interrupt follows it. */
  def acknowledge(): Unit = withLock(lock) { acknowledged = true }

  /** Runs `interrupt` unless the run has acknowledged its stop. */
  def interruptUnlessAcknowledged(interrupt: () => Unit): Unit =
    withLock(lock)(if !acknowledged then interrupt())

/**
 * The runtime's [[RunHandle]]. [[launch]] starts the run thread, which completes `result` on every
 * exit - normal, interrupted, or by an unexpected throwable - after releasing the thread claim, so
 * a caller that has seen the result can start the next run at once.
 *
 * Every subscription made for the run - its `observation`, and each [[subscribe]] - is given the
 * run's end-of-run barrier ([[Dispatched.endOfRun]]) once the run has handed its last event to the
 * hub: by the run thread as it exits, before it releases the thread claim - so no event of a later
 * run on the thread can be queued ahead of the barrier - or at once for a subscription made after
 * that. The result is set just after.
 */
final private[graph] class DefaultRunHandle[O](
  val threadId: ThreadId,
  val runId: RunId,
  claimSeq: Long,
  signal: StopSignal,
  subscribeFrom: (Long, Int, StreamEvent => Unit) => Result[Dispatched],
  val observation: Option[Dispatched]
) extends RunHandle[O]:

  private val result                      = new CompletableFuture[RunResult[O]]()
  @volatile private var runThread: Thread = null

  /**
   * Completed by the run thread as it exits, before it releases the thread claim: every event of
   * the run has been handed to the hub, and none of a later run on the thread can have been.
   */
  private val handedOver = new CompletableFuture[Unit]()

  observation.foreach(endsWithRun)

  /** Gives `subscription` this run's end-of-run barrier once the run has handed over its last event. */
  private def endsWithRun(subscription: Dispatched): Unit =
    handedOver.whenComplete((_, _) => subscription.endOfRun(runId)): Unit

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

  /** See [[DefaultRunHandle.awaitEnd]]. */
  private[graph] def awaitEnd(timeout: FiniteDuration): Boolean =
    val deadline = System.nanoTime() + timeout.toNanos
    // the result is set after the run releases its thread claim, so a done result means the thread is free
    @tailrec def waitFor(interrupted: Boolean): (Boolean, Boolean) =
      val remaining = deadline - System.nanoTime()
      if result.isDone || remaining <= 0 then (result.isDone, interrupted)
      else
        DefaultRunHandle.guarded(result.get(remaining, TimeUnit.NANOSECONDS)) match
          case Left(_: InterruptedException) => waitFor(interrupted = true)
          case _                             => (result.isDone, interrupted)
    val (ended, interrupted) = waitFor(Thread.interrupted())
    if interrupted then Thread.currentThread().interrupt()
    ended

  /**
   * Records `stopCause` unless a cause is already recorded - including the run's own
   * [[StopCause.Finishing]] - and only then interrupts a live run that has not acknowledged its
   * stop (see [[StopSignal]]). A stop whose cause was not the first sends no interrupt at all: the
   * first cause's stop interrupts, or the run is finishing, or it is closing a stop it detected.
   */
  private[graph] def stop(stopCause: StopCause): Unit =
    // the thread is started before the handle is returned, and an interrupt on a started thread
    // that has not yet run sets its flag, which the loop checks before every superstep
    if signal.cause.compareAndSet(None, Some(stopCause)) then
      beforeInterrupt()
      // a stop before the run thread exists records only its cause, which the loop checks before every superstep
      signal.interruptUnlessAcknowledged(() => if !result.isDone && runThread != null then runThread.interrupt())

  /** A test hook run by [[stop]] between recording its cause and interrupting; a no-op by default. */
  @volatile private[graph] var beforeInterrupt: () => Unit = () => ()

  def subscribe(capacity: Int = 1024)(listener: StreamEvent => Unit): Result[Subscription] =
    subscribeFrom(claimSeq - 1, capacity, listener).map { subscription =>
      endsWithRun(subscription)
      subscription
    }

  /**
   * Starts the run thread, running `body`. A throwable escaping it becomes `crashed(throwable)`;
   * `release` runs before the result is set, on every exit. With a `deadline` (a `System.nanoTime`
   * value), it then starts a virtual thread named `llm4s-deadline-<threadId>` that stops the run
   * with [[StopCause.Expired]] when the deadline passes; see [[expireAt]]. The run's claim is renewed
   * by the runtime's heartbeat, which `release` stops before it gives the claim back.
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
      // the run ended first; any other failure is impossible: `result` is only ever completed
      // normally and nothing interrupts this thread
      case _ => ()

  /**
   * The run thread's body. `crashed` must not throw (see [[DefaultRunHandle.guarded]]). `result` is
   * completed on every exit: by `close`, after `release`, even if `release` throws or something
   * escapes [[DefaultRunHandle.guarded]] (a `ControlThrowable`). `close` first settles the outcome -
   * `crashed` for an abnormal exit, which closes the run's committer and so hands over its last
   * events - then gives the run's subscriptions their end-of-run barriers, and only then releases the
   * thread, so the barriers follow every event of the run and precede any of a later run.
   * `release` gives the claim back with the interrupt flag clear, so that a cancelled or expired run's
   * flag cannot make a store refuse it, and sets the flag again afterwards.
   */
  private def run(body: () => RunResult[O], crashed: Throwable => RunResult[O], release: () => Unit): Unit =
    var outcome: Option[RunResult[O]] = None
    Using.resource(new AutoCloseable {
      def close(): Unit =
        settle(() => outcome.getOrElse(crashed(new IllegalStateException("run thread ended abnormally"))), release)
        runThreadExited.countDown()
    })(_ => outcome = Some(DefaultRunHandle.guarded(body()).fold(crashed, identity)))

  /** Taken by whichever settles the run: the run thread as it exits, or [[endWithin]]. */
  private val settling = new AtomicBoolean(false)

  /**
   * Settles the run from the run thread as it exits, unless [[endWithin]] has taken it: `outcome` is
   * computed, then the run is taken and [[finish]]ed. The outcome is computed before the run is taken -
   * closing a crashed run's committer can wait on the store - so that the watchdog can still settle a
   * run whose thread is stuck computing it. Whoever takes the run second sets nothing, so a run has one
   * result, and its thread is freed once - never after a later run has taken it.
   */
  private def settle(outcome: () => RunResult[O], release: () => Unit): Unit =
    val settled = outcome()
    if settling.compareAndSet(false, true) then finish(settled, release)

  /**
   * Ends a run that has been taken: the subscriptions are given their end-of-run barriers, `release`
   * runs, and the result is set. Nothing here waits on the store but what `release` bounds.
   */
  private def finish(settled: RunResult[O], release: () => Unit): Unit =
    DefaultRunHandle.guarded(handedOver.complete(())): Unit
    DefaultRunHandle.guarded(release()): Unit
    result.complete(settled): Unit

  private val bounding = new AtomicBoolean(false)

  /**
   * Bounds the run's end once its claim is found lost, whatever the run is doing: a virtual thread,
   * `llm4s-lost-<threadId>`, waits for the result until `deadline` (a `System.nanoTime` value) and, if
   * the run thread has not taken the run to settle it by then, takes it, and only then computes
   * `outcome`, which must neither throw nor wait on the store, and [[finish]]es the run with it. The run
   * thread may be stuck in a store call that ignores interrupts, as a commit can be, even one of its
   * outcome, which no stop interrupts; when it does return, it changes nothing. Taking the run first
   * means `outcome` - which fences the run and cuts its own subscriptions at what the hub has handed
   * over ([[AbandonedWrites]]) - applies only to a run the watchdog settles: a run thread that wins
   * instead ends the run with its own outcome, and the watchdog neither fences nor cuts it. Only the first call
   * starts the thread.
   */
  private[graph] def endWithin(deadline: Long, outcome: () => RunResult[O], release: () => Unit): Unit =
    if bounding.compareAndSet(false, true) then
      Thread
        .ofVirtual()
        .name(s"llm4s-lost-${threadId.value}")
        .start { () =>
          val wait = math.max(0L, deadline - System.nanoTime())
          DefaultRunHandle.guarded(result.get(wait, TimeUnit.NANOSECONDS)) match
            // `outcome` fences and cuts the run: only once the watchdog has taken it
            case Left(_: TimeoutException) if settling.compareAndSet(false, true) =>
              val settled = outcome()
              settlingWithoutRunThread()
              finish(settled, release)
            // settled first; nothing else interrupts this thread
            case _ => ()
        }: Unit

  /** A test hook run by [[endWithin]]'s thread once it has computed its outcome; a no-op by default. */
  @volatile private[graph] var settlingWithoutRunThread: () => Unit = () => ()

  /** Opened as the run thread exits; for tests. */
  private[graph] val runThreadExited = new java.util.concurrent.CountDownLatch(1)

private[agent] object DefaultRunHandle:

  /**
   * Waits at most `timeout` for `handle`'s run to end, whatever interrupts the waiting thread, and
   * returns whether it ended. An interrupt received before or during the wait is kept: the flag is
   * set again on return. For a caller that has cancelled the run and must not return while the run
   * still holds its thread. Every handle the runtime admits is a [[DefaultRunHandle]], which waits on
   * its result; any other [[RunHandle]] has its `status` polled, every millisecond.
   */
  def awaitEnd(handle: RunHandle[?], timeout: FiniteDuration): Boolean = handle match
    case own: DefaultRunHandle[?] => own.awaitEnd(timeout)
    case other =>
      val deadline = System.nanoTime() + timeout.toNanos
      @tailrec def poll(interrupted: Boolean): Boolean =
        if other.status != RunStatus.Running || System.nanoTime() >= deadline then interrupted
        else
          LockSupport.parkNanos(math.min(1_000_000L, math.max(0L, deadline - System.nanoTime())))
          poll(Thread.interrupted() || interrupted)
      if poll(Thread.interrupted()) then Thread.currentThread().interrupt()
      other.status != RunStatus.Running

  /**
   * Runs `body`, returning anything it throws - an `InterruptedException` or a fatal error included -
   * as `Left`; only a `ControlThrowable` propagates. An interrupt's flag is left cleared.
   */
  def guarded[A](body: => A): Either[Throwable, A] =
    CancelledError.catchInterrupt(allCatch.either(body)).fold(Left(_), identity)
