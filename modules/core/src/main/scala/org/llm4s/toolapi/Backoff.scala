package org.llm4s.toolapi

import java.util.concurrent.{ CompletableFuture, Executor, TimeUnit }
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration

/**
 * A delay that holds no thread, for the asynchronous retry path of [[ToolRegistry]].
 *
 * The wait is handed to the JDK's own delay scheduler (`CompletableFuture.delayedExecutor`), which keeps a
 * single shared daemon thread for every delay in the JVM: nothing here owns a thread pool, a thread never
 * blocks for the duration, and an idle JVM can exit. That scheduler thread does nothing but complete a
 * promise; whatever follows the delay is dispatched by the caller's `ExecutionContext`, never run on it.
 *
 * Synchronous retries cannot use this: a synchronous caller has no continuation to resume, so it waits on its
 * own thread (see `ToolRegistry.execute`).
 */
private[toolapi] object Backoff {

  /** Runs the task on the delay scheduler's thread; the task is a promise completion and returns at once. */
  private val onSchedulerThread: Executor = (task: Runnable) => task.run()

  /** A future that completes after `delay`, without parking a thread; already complete for a zero delay. */
  def after(delay: FiniteDuration): Future[Unit] =
    if (delay.toNanos <= 0L) {
      Future.unit
    } else {
      val done = Promise[Unit]()
      CompletableFuture
        .delayedExecutor(delay.toNanos, TimeUnit.NANOSECONDS, onSchedulerThread)
        .execute(() => done.trySuccess(()): Unit)
      done.future
    }
}
