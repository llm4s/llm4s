package org.llm4s.toolapi

import java.util.concurrent.{ CompletableFuture, TimeUnit }
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration.FiniteDuration

/**
 * A delay that holds no thread, for the asynchronous retry path of [[ToolRegistry]].
 *
 * The wait is handed to the JDK's own delay scheduler (`CompletableFuture.delayedExecutor`), which keeps a
 * single shared daemon thread for every delay in the JVM: nothing here owns a thread pool, a thread never
 * blocks for the duration, and an idle JVM can exit.
 *
 * The promise is deliberately completed on the JDK's default asynchronous executor, not on the scheduler
 * thread itself: a caller observing the future through an inline `ExecutionContext` (such as
 * `ExecutionContext.parasitic`) runs its continuation on whichever thread completes the promise, and a slow
 * retried attempt on the JVM-wide scheduler singleton would hold back every other delayed task in the
 * process. The scheduler thread therefore only triggers the handoff; `ToolRegistryBackoffSpec` pins this.
 *
 * Synchronous retries cannot use this: a synchronous caller has no continuation to resume, so it waits on its
 * own thread (see `ToolRegistry.execute`).
 */
private[toolapi] object Backoff {

  /** A future that completes after `delay`, without parking a thread; already complete for a zero delay. */
  def after(delay: FiniteDuration): Future[Unit] =
    if (delay.toNanos <= 0L) {
      Future.unit
    } else {
      val done = Promise[Unit]()
      CompletableFuture
        .delayedExecutor(delay.toNanos, TimeUnit.NANOSECONDS)
        .execute(() => done.trySuccess(()): Unit)
      done.future
    }
}
