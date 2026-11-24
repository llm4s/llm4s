package org.llm4s.toolapi

import org.llm4s.annotation.Experimental
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

/**
 * Determines how several tool calls are executed by [[ToolExecutor.executeAll]].
 *
 * Choose [[ToolExecutionStrategy.Sequential]] when calls depend on one another
 * or mutate shared state. Choose [[ToolExecutionStrategy.Parallel]] when calls
 * are independent and mostly wait on I/O. Choose
 * [[ToolExecutionStrategy.ParallelWithLimit]] when calls can run concurrently
 * but external systems or local resources need bounded concurrency.
 *
 * @example
 * {{{
 * val results = ToolExecutionStrategy.parallelWithLimit(2).map { strategy =>
 *   ToolExecutor.executeAll(registry, requests, strategy)
 * }
 * }}}
 */
@Experimental
sealed trait ToolExecutionStrategy

/**
 * Factory and singleton values for [[ToolExecutionStrategy]].
 */
@Experimental
object ToolExecutionStrategy {

  /**
   * Execute tools one at a time, in order.
   *
   * This is the safest strategy and the default behavior. Use it when tools
   * have dependencies on each other, order of execution matters, or you are
   * debugging tool behavior.
   */
  case object Sequential extends ToolExecutionStrategy

  /**
   * Execute all tools simultaneously.
   *
   * Best for independent, I/O-bound tools such as multiple API calls,
   * database queries, or read-only file operations. Avoid it for side effects
   * that must not overlap, and watch for rate limiting from external APIs.
   */
  case object Parallel extends ToolExecutionStrategy

  /**
   * Execute tools in parallel with a concurrency limit.
   *
   * Balances parallel execution with resource constraints. Use it when
   * external APIs have rate limits, the system has limited local resources, or
   * parallel execution is useful but must remain controlled.
   *
   * Build one with [[ToolExecutionStrategy.parallelWithLimit]], which checks the limit.
   *
   * @param maxConcurrency Maximum number of tools executing simultaneously; positive
   */
  final case class ParallelWithLimit private[ToolExecutionStrategy] (maxConcurrency: Int) extends ToolExecutionStrategy

  /**
   * [[ParallelWithLimit]] with at most `maxConcurrency` tools executing at once, or a `ValidationError` when
   * `maxConcurrency` is not positive.
   */
  def parallelWithLimit(maxConcurrency: Int): Result[ParallelWithLimit] =
    if (maxConcurrency > 0) Right(new ParallelWithLimit(maxConcurrency))
    else Left(ValidationError("maxConcurrency", s"must be positive, but is $maxConcurrency"))

  /**
   * Default strategy: Sequential execution.
   */
  val default: ToolExecutionStrategy = Sequential
}
