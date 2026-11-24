package org.llm4s.toolapi

import org.llm4s.annotation.Experimental
import org.llm4s.error.CancelledError

import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ Await, ExecutionContext, Future, Promise, blocking }
import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * Runs the calls of a [[ToolRegistry]] with a timeout, with retries, asynchronously or as a batch.
 *
 * A registry itself only runs one call on the caller's thread ([[ToolRegistry.execute]]). Everything here is
 * built on that one method, so it applies to any registry, including one that finds its tools elsewhere
 * (`MCPToolRegistry`).
 * {{{
 * ToolExecutor.execute(registry, request, config)               // blocking, on this thread
 * ToolExecutor.executeAsync(registry, request, config)          // Future
 * ToolExecutor.executeAll(registry, requests, strategy, config) // Future of the results, in request order
 * }}}
 *
 * The agent runtime does not use this: it runs its tools itself and has its own middleware for timeouts and
 * retries. Only the asynchronous methods take an `ExecutionContext`.
 */
@Experimental
object ToolExecutor {

  private type Outcome = Either[ToolCallError, ujson.Value]

  /**
   * Executes a tool call on the calling thread with the timeout and retry policy of `config`.
   *
   * With [[ToolExecutionConfig.default]] (no timeout, no retry) this is `registry.execute(request)`. A backoff
   * between two attempts waits on the calling thread, interruptibly: an interruption ends the call as
   * [[ToolCallError.Cancelled]].
   */
  def execute(registry: ToolRegistry, request: ToolCallRequest, config: ToolExecutionConfig): Outcome =
    config.retryPolicy match {
      case None =>
        runOneAttemptWithTimeout(registry, request, config.timeout)
      case Some(policy) =>
        var attempt             = 0
        var lastResult: Outcome = null
        while (attempt < policy.maxAttempts) {
          lastResult = runOneAttemptWithTimeout(registry, request, config.timeout)
          retryDelay(lastResult, attempt, policy) match {
            case Some(delay) =>
              attempt += 1
              // A synchronous caller has nothing to resume, so the wait is on its own thread. It is
              // interruptible: an interruption ends the wait as a cancellation, never as another attempt.
              if (delay.toMillis > 0) {
                val slept = CancelledError.catchInterrupt(blocking(Thread.sleep(delay.toMillis)))
                if (slept.isLeft) {
                  Thread.currentThread().interrupt()
                  return Left(ToolCallError.Cancelled(request.functionName))
                }
              }
            case None => return lastResult
          }
        }
        lastResult
    }

  /**
   * Executes a single tool attempt with an optional wall-clock timeout.
   *
   * When a timeout is configured, the tool runs on a dedicated virtual thread and this
   * method's caller thread joins it with a deadline. If the tool exceeds the deadline,
   * this method returns [[ToolCallError.Timeout]] and performs a best-effort interrupt of
   * the worker thread. The interrupt signal will unblock standard Java/Scala interruptible
   * operations such as `Thread.sleep`, socket I/O, and `Await.result`. CPU-bound
   * operations that never call a blocking primitive cannot be interrupted this way;
   * that is a JVM-level limitation. Because the worker thread is never shared with any
   * other call, a late-delivered interrupt can never leak onto unrelated work.
   */
  private def runOneAttemptWithTimeout(
    registry: ToolRegistry,
    request: ToolCallRequest,
    timeoutOpt: Option[FiniteDuration]
  ): Outcome =
    timeoutOpt match {
      case None =>
        registry.execute(request)
      case Some(duration) =>
        val promise = Promise[Outcome]()

        // The worker runs on a dedicated virtual thread instead of a shared pool. That is required, not
        // just tidier: interrupting a *pooled* thread is only safe if the interrupt is delivered before
        // the pool can reuse that thread for unrelated work, and nothing guarantees that ordering under
        // load. A thread owned solely by this call cannot be reused by anything else, so an
        // interrupt delivered after it has already finished is at worst a harmless no-op
        // against a dead thread - never a leak onto a later, unrelated call.
        //
        // The timeout itself is enforced by this method's own caller thread joining the
        // worker with a deadline, rather than by a shared background scheduler. A scheduler
        // shared across every concurrent tool call is a coordination bottleneck in exactly
        // the scenario this races against - many calls timing out concurrently under CI's
        // parallel test load - since a delayed dispatch on that single shared thread looks
        // identical to a delayed interrupt. `Thread.join` has no such shared resource: each
        // call's wait is local to its own caller thread.
        val workerThread = Thread
          .ofVirtual()
          .name(s"tool-exec-${request.functionName}")
          .start { () =>
            // InterruptedException is deliberately excluded from NonFatal (it signals
            // cooperative cancellation, not a defect), but here it's the routine outcome
            // whenever the caller thread's join times out and interrupts this thread
            // mid-call, so it must be caught alongside NonFatal - otherwise it escapes
            // uncaught and `promise` is never resolved from this side. That's harmless
            // (the caller's own timeoutError below still resolves the promise), but the
            // uncaught exception would otherwise propagate to this virtual thread's
            // default uncaught-exception handler for no useful purpose.
            // scalafix:off DisableSyntax.NoKeywordCatch
            val resultOrEx: Either[Throwable, Outcome] =
              try Right(registry.execute(request))
              catch {
                case NonFatal(t)             => Left(t)
                case t: InterruptedException => Left(t)
              }
            // scalafix:on
            promise.trySuccess(
              resultOrEx.fold(t => Left(ToolCallError.ExecutionError(request.functionName, t)), identity)
            )
          }

        val joined = CancelledError.catchInterrupt(workerThread.join(duration.toMillis))
        if (joined.isLeft) {
          // The caller was interrupted while waiting. Claim the promise for Cancelled before
          // interrupting the worker, for the same ordering reason as the timeout below, then
          // restore the flag the throw cleared. No Await: it would throw on an interrupted thread.
          val cancelled = Left(ToolCallError.Cancelled(request.functionName))
          promise.trySuccess(cancelled): Unit
          workerThread.interrupt()
          Thread.currentThread().interrupt()
          cancelled
        } else {
          if (workerThread.isAlive) {
            // Claim the promise for Timeout *before* interrupting the worker. If the interrupt
            // were sent first, the worker's own catch block could race ahead and call
            // promise.trySuccess(ExecutionError(InterruptedException)) before this thread's
            // trySuccess(Timeout) runs - whichever call reaches the promise first wins, and
            // interrupt delivery has no ordering guarantee relative to this thread's next line.
            // Claiming first makes the outcome deterministic: the worker's later trySuccess is
            // then always the no-op, regardless of how quickly it reacts to the interrupt.
            promise.trySuccess(Left(ToolCallError.Timeout(request.functionName, duration))): Unit
            workerThread.interrupt()
          }

          // The promise is already completed by this point - either the worker resolved it
          // before the join deadline, or the timeout branch above just did. This Await is a
          // formality that returns immediately; its duration is a defensive upper bound only.
          Await.result(promise.future, 1.second)
        }
    }

  /**
   * Executes a tool call asynchronously, with the timeout and retry policy of `config`.
   *
   * Each attempt runs on a thread of `ec` and holds it for the attempt only; tool execution typically blocks
   * on I/O, so the attempt is marked `blocking`. The backoff between two attempts holds no thread: it is a
   * scheduled delay, after which the next attempt is dispatched to `ec`, so a small pool keeps serving other
   * work while many calls wait to retry. (The synchronous [[execute]] still waits on its caller's thread,
   * interruptibly, because a synchronous caller has nothing to resume.)
   */
  def executeAsync(
    registry: ToolRegistry,
    request: ToolCallRequest,
    config: ToolExecutionConfig = ToolExecutionConfig.default
  )(implicit ec: ExecutionContext): Future[Either[ToolCallError, ujson.Value]] =
    config.retryPolicy match {
      case Some(policy) => retryAsync(registry, request, config.timeout, policy, 0)
      case None         => attemptOnPool(registry, request, config.timeout)
    }

  /** One attempt (with its optional timeout) on a pool thread; the thread is held for the attempt only. */
  private def attemptOnPool(
    registry: ToolRegistry,
    request: ToolCallRequest,
    timeout: Option[FiniteDuration]
  )(implicit ec: ExecutionContext): Future[Outcome] =
    Future(blocking {
      val result = runOneAttemptWithTimeout(registry, request, timeout)
      // A Cancelled call left the flag set on this pool thread; the Future carries the outcome,
      // and a flag left behind would cancel the next, unrelated task the pool runs here.
      result match {
        case Left(_: ToolCallError.Cancelled) => Thread.interrupted(): Unit
        case _                                => ()
      }
      result
    })

  /**
   * Attempts, and between attempts waits out the backoff without holding a thread: a positive delay is
   * scheduled ([[Backoff.after]]) and the next attempt is dispatched to `ec` when it elapses. Which errors
   * are retried and how long each wait is are decided by the same [[retryDelay]] as the synchronous loop.
   *
   * Zero-delay retries are drained by a plain loop rather than by recursing through an already-completed
   * future: under an inline, non-trampolining `ExecutionContext` (for example
   * `ExecutionContext.fromExecutor(_.run())`) every continuation runs on the caller's stack, so a recursive
   * `flatMap` chain grew by a constant per attempt and a policy with `Duration.Zero` and a large
   * `maxAttempts` overflowed it. An attempt that completes on this very stack is handled inside the loop;
   * one that completes elsewhere re-enters the loop from its `onComplete`, which such a context dispatches
   * on the completing thread's fresh stack.
   */
  private def retryAsync(
    registry: ToolRegistry,
    request: ToolCallRequest,
    timeout: Option[FiniteDuration],
    policy: ToolRetryPolicy,
    attempt: Int
  )(implicit ec: ExecutionContext): Future[Outcome] = {
    val done = Promise[Outcome]()

    // Settles `done`, schedules a delayed retry, or returns the next attempt index for an immediate retry.
    def settle(outcome: Try[Outcome], attempt: Int): Option[Int] =
      outcome match {
        case Failure(t) =>
          done.tryFailure(t): Unit
          None
        case Success(result) =>
          retryDelay(result, attempt, policy) match {
            case None =>
              done.trySuccess(result): Unit
              None
            case Some(delay) if delay.toNanos > 0L =>
              Backoff.after(delay).onComplete(_ => loop(attempt + 1))
              None
            case Some(_) =>
              Some(attempt + 1)
          }
      }

    def loop(startAttempt: Int): Unit = {
      var next = Option(startAttempt)
      while (next.isDefined) {
        val attempt   = next.get
        val attempted = attemptOnPool(registry, request, timeout)
        next = attempted.value match {
          // Completed on this stack (inline ExecutionContext): stay in this loop, constant stack.
          case Some(outcome) => settle(outcome, attempt)
          // Completes elsewhere: the callback starts a fresh loop on whichever stack runs it.
          case None =>
            attempted.onComplete(outcome => settle(outcome, attempt).foreach(loop))
            None
        }
      }
    }

    loop(attempt)
    done.future
  }

  /**
   * Executes several tool calls with a configurable strategy.
   *
   * @param requests The tool call requests to execute
   * @param strategy Sequential, parallel, or parallel with a concurrency limit
   * @param config   The timeout and retry policy of each call
   * @return The results, in the same order as `requests`
   */
  def executeAll(
    registry: ToolRegistry,
    requests: Seq[ToolCallRequest],
    strategy: ToolExecutionStrategy = ToolExecutionStrategy.default,
    config: ToolExecutionConfig = ToolExecutionConfig.default
  )(implicit ec: ExecutionContext): Future[Seq[Either[ToolCallError, ujson.Value]]] =
    strategy match {
      case ToolExecutionStrategy.Sequential =>
        requests.foldLeft(Future.successful(Seq.empty[Outcome])) { (accFuture, request) =>
          accFuture.flatMap(acc => executeAsync(registry, request, config).map(result => acc :+ result))
        }

      case ToolExecutionStrategy.Parallel =>
        Future.traverse(requests)(req => executeAsync(registry, req, config))

      case ToolExecutionStrategy.ParallelWithLimit(maxConcurrency) =>
        executeWithLimit(registry, requests, maxConcurrency, config)
    }

  /**
   * Execute requests in parallel with a concurrency limit using a sliding window.
   * This implementation avoids Head-of-Line (HoL) blocking.
   */
  private def executeWithLimit(
    registry: ToolRegistry,
    requests: Seq[ToolCallRequest],
    maxConcurrency: Int,
    config: ToolExecutionConfig
  )(implicit ec: ExecutionContext): Future[Seq[Outcome]] =
    if (requests.isEmpty) {
      Future.successful(Seq.empty)
    } else {
      val tasks        = requests.toVector
      val totalTasks   = tasks.length
      val currentIndex = new AtomicInteger(0)
      val results      = new Array[Outcome](totalTasks)

      def worker(): Future[Unit] = {
        val idx = currentIndex.getAndIncrement()
        if (idx >= totalTasks) {
          Future.successful(())
        } else {
          val request = tasks(idx)
          executeAsync(registry, request, config)
            .recover { case NonFatal(ex) =>
              Left(ToolCallError.ExecutionError(request.functionName, new Exception(ex.getMessage)))
            }
            .flatMap { result =>
              results(idx) = result
              worker()
            }
        }
      }

      val workerCount = math.min(maxConcurrency, totalTasks)
      val workers     = (1 to workerCount).map(_ => worker())

      Future.sequence(workers).map(_ => results.toSeq)
    }

  /**
   * How long to wait before the next attempt, or `None` when `result` is final: a success, an error that is
   * not retryable (cancellation included), or the last attempt the policy allows. `attempt` is 0 for the
   * initial try. Shared by the synchronous and the asynchronous retry loops so that they cannot disagree.
   * `sample` draws the policy's jitter from `[0, 1)`; it is only called when a retry follows, and tests inject it.
   */
  private[toolapi] def retryDelay(
    result: Either[ToolCallError, ujson.Value],
    attempt: Int,
    policy: ToolRetryPolicy,
    sample: () => Double = () => ThreadLocalRandom.current().nextDouble()
  ): Option[FiniteDuration] =
    result match {
      case Left(err) if ToolCallError.isRetryable(err) && attempt + 1 < policy.maxAttempts =>
        Some(policy.jitteredDelayBeforeAttempt(attempt + 1, sample()))
      case _ => None
    }
}
