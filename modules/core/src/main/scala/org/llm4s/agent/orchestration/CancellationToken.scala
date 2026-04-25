// scalafix:off
package org.llm4s.agent.orchestration

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ Future, Promise }

/**
 * Token for cancelling long-running orchestration operations.
 * Thread-safe and can be checked from any thread.
 *
 * @note For operations with many nodes, use `cachedCancellationFuture` instead of
 *       `cancellationFuture` to avoid callback accumulation.
 * @example
 * {{{
 * val token = CancellationToken()
 * val runner = PlanRunner()
 * val result = runner.execute(plan, inputs, token)
 *
 * // Cancel from another thread
 * token.cancel()
 * }}}
 */
class CancellationToken {
  private val cancelled = new AtomicBoolean(false)
  private val callbacks = new java.util.concurrent.ConcurrentLinkedQueue[() => Unit]()

  /**
   * Check if cancellation has been requested
   */
  def isCancelled: Boolean = cancelled.get()

  /**
   * Request cancellation
   */
  def cancel(): Unit =
    if (cancelled.compareAndSet(false, true)) {
      // Execute all registered callbacks
      while (!callbacks.isEmpty) {
        val callback = callbacks.poll()
        if (callback != null) {
          try
            callback()
          catch {
            case _: Exception => // Ignore callback exceptions
          }
        }
      }
    }

  /**
   * Register a callback to be called when cancellation is requested
   */
  def onCancel(callback: => Unit): Unit = {
    val cb = () => callback
    if (cancelled.get()) {
      // Already cancelled, execute immediately
      cb()
    } else {
      callbacks.offer(cb)
      // Check again in case we were cancelled while adding
      if (cancelled.get() && callbacks.remove(cb)) {
        cb()
      }
    }
  }

  /**
   * Create a Future that fails with CancellationException when cancelled.
   * Note: For long-running operations with many nodes, consider caching this
   * future to avoid accumulating callbacks.
   */
  def cancellationFuture: Future[Nothing] = {
    val promise = Promise[Nothing]()
    onCancel {
      promise.tryFailure(new CancellationException("Operation cancelled"))
    }
    promise.future
  }

  /**
   * Create a cached cancellation future that can be reused across multiple operations
   * to avoid callback accumulation.
   */
  lazy val cachedCancellationFuture: Future[Nothing] = cancellationFuture

  /**
   * Check cancellation and throw if cancelled
   */
  def throwIfCancelled(): Unit =
    if (isCancelled) {
      throw new CancellationException("Operation cancelled")
    }
}

/**
 * Exception thrown when an operation is cancelled
 */
class CancellationException(message: String) extends RuntimeException(message)

object CancellationToken {

  /**
   * Create a new cancellation token
   */
  def apply(): CancellationToken = new CancellationToken()

  /**
   * A token that is never cancelled (for operations that can't be cancelled)
   */
  val none: CancellationToken = new CancellationToken() {
    override def cancel(): Unit       = ()
    override def isCancelled: Boolean = false
  }
}
