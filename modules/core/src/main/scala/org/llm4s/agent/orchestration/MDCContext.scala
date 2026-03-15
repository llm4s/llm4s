// scalafix:off
package org.llm4s.agent.orchestration

import org.slf4j.MDC
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Thread-safe MDC (Mapped Diagnostic Context) management for async operations.
 *
 * SLF4J MDC is thread-local, so context is lost when work moves across threads.
 * This utility captures, restores, and propagates MDC state to ensure consistent
 * logging context in Future-based and ExecutionContext-based pipelines.
 */
object MDCContext {

  /**
   * Capture the current thread's MDC context as an immutable map.
   *
   * @return a snapshot of all MDC key-value pairs, or an empty map if none are set
   */
  def capture(): Map[String, String] = {
    val context = MDC.getCopyOfContextMap()
    if (context != null) {
      import scala.jdk.CollectionConverters._
      context.asScala.toMap
    } else {
      Map.empty
    }
  }

  /**
   * Replace the current thread's MDC context with the given map.
   *
   * @param context the MDC key-value pairs to install
   */
  def set(context: Map[String, String]): Unit = {
    MDC.clear()
    context.foreach { case (key, value) =>
      MDC.put(key, value)
    }
  }

  /**
   * Execute a block with the given MDC context, restoring the previous context afterward.
   *
   * @param context MDC key-value pairs to set during execution
   * @param block the code to execute
   * @return the result of `block`
   */
  def withContext[T](context: Map[String, String])(block: => T): T = {
    val previousContext = capture()
    try {
      set(context)
      block
    } finally set(previousContext)
  }

  /**
   * Execute a block with additional MDC values merged into the current context.
   * The previous context is restored afterward.
   *
   * @param values key-value pairs to add to the current MDC
   * @param block the code to execute
   * @return the result of `block`
   */
  def withValues[T](values: (String, String)*)(block: => T): T = {
    val previousContext = capture()
    try {
      values.foreach { case (key, value) =>
        MDC.put(key, value)
      }
      block
    } finally set(previousContext)
  }

  /**
   * Wrap an [[ExecutionContext]] so that MDC context is captured at submission time
   * and restored on the executing thread before each runnable runs.
   *
   * @param underlying the execution context to wrap
   * @return an MDC-preserving execution context
   */
  def preservingExecutionContext(underlying: ExecutionContext): ExecutionContext = new ExecutionContext {
    def execute(runnable: Runnable): Unit = {
      val capturedContext = capture()
      underlying.execute(new Runnable {
        def run(): Unit = withContext(capturedContext)(runnable.run())
      })
    }

    def reportFailure(cause: Throwable): Unit = underlying.reportFailure(cause)
  }

  /**
   * Map over a Future while preserving the caller's MDC context on the callback thread.
   *
   * @param future the future to wrap
   * @param ec implicit execution context for the map callback
   * @return a future whose map callback runs with the caller's MDC context
   */
  def preservingFuture[T](future: Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val capturedContext = capture()
    future.map(value => withContext(capturedContext)(value))(preservingExecutionContext(ec))
  }

  /**
   * Remove specific keys from the current thread's MDC.
   *
   * @param keys the MDC keys to remove
   */
  def cleanup(keys: String*): Unit =
    keys.foreach(MDC.remove)
}
