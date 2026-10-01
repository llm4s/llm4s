// scalafix:off DisableSyntax.NoKeywordCatch
package org.llm4s.agent.orchestration

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.{ Future, Promise }

/**
 * Token for cancelling long-running orchestration operations.
 * Thread-safe and can be checked from any thread.
 *
 * @note [[whenCancelled]] is one shared future, so racing many operations against it adds
 *       no callbacks per operation.
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
   * A future that completes successfully once cancellation is requested, and never otherwise.
   *
   * Race an operation against it to stop waiting on cancel, mapping it to the error the
   * operation should report: `Future.firstCompletedOf(List(op, token.whenCancelled.map(_ => Left(...))))`.
   * It is created once per token, so racing many operations against it is cheap.
   */
  lazy val whenCancelled: Future[Unit] = {
    val promise = Promise[Unit]()
    onCancel { promise.trySuccess(()); () }
    promise.future
  }
}

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
