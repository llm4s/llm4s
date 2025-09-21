// scalafix:off
package org.llm4s.agent.orchestration

import org.slf4j.MDC
import scala.concurrent.{ ExecutionContext, Future }

/**
 * Thread-safe MDC context management for async operations.
 * Preserves MDC context across thread boundaries.
 * This file needs try-finally to ensure MDC context is restored.
 */
object MDCContext {

  /**
   * Capture current MDC context
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
   * Set MDC context from captured map
   */
  def set(context: Map[String, String]): Unit = {
    MDC.clear()
    context.foreach { case (key, value) =>
      MDC.put(key, value)
    }
  }

  /**
   * Run a block with specific MDC context
   */
  def withContext[T](context: Map[String, String])(block: => T): T = {
    val previousContext = capture()
    try {
      set(context)
      block
    } finally set(previousContext)
  }

  /**
   * Run a block with additional MDC values
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
   * Create an ExecutionContext that preserves MDC context
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
   * Wrap a Future to preserve MDC context
   */
  def preservingFuture[T](future: Future[T])(implicit ec: ExecutionContext): Future[T] = {
    val capturedContext = capture()
    future.map(value => withContext(capturedContext)(value))(preservingExecutionContext(ec))
  }

  /**
   * Clean up MDC values after an operation
   */
  def cleanup(keys: String*): Unit =
    keys.foreach(MDC.remove)
}
