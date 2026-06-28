package org.llm4s.toolapi

/**
 * Determines how multiple tool calls in a single agent step are executed by
 * [[ToolRegistry.executeAll]].
 *
 * Choose [[ToolExecutionStrategy.Sequential]] when calls depend on one another
 * or mutate shared state. Choose [[ToolExecutionStrategy.Parallel]] when calls
 * are independent and mostly wait on I/O. Choose
 * [[ToolExecutionStrategy.ParallelWithLimit]] when calls can run concurrently
 * but external systems or local resources need bounded concurrency.
 *
 * @example
 * {{{
 * val results = registry.executeAll(
 *   requests,
 *   strategy = ToolExecutionStrategy.ParallelWithLimit(2)
 * )
 *
 * val agentState = agent.runWithStrategy(
 *   query = "Get weather in London, Paris, and Tokyo",
 *   tools = registry,
 *   toolExecutionStrategy = ToolExecutionStrategy.Parallel,
 *   maxSteps = Some(5)
 * )
 * }}}
 */
sealed trait ToolExecutionStrategy

/**
 * Factory and singleton values for [[ToolExecutionStrategy]].
 */
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
   * @param maxConcurrency Maximum number of tools executing simultaneously
   */
  final case class ParallelWithLimit(maxConcurrency: Int) extends ToolExecutionStrategy {
    require(maxConcurrency > 0, "maxConcurrency must be positive")
  }

  /**
   * Default strategy: Sequential execution.
   */
  val default: ToolExecutionStrategy = Sequential
}
