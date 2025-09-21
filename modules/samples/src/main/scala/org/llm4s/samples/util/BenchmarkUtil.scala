package org.llm4s.samples.util

/**
 * Utility class for benchmarking operations
 *
 * Separates timing/performance measurement logic from business logic
 * for better code organization and maintainability.
 */
object BenchmarkUtil {

  /**
   * Simple timer to measure execution time of operations
   */
  case class Timer(startTime: Long = System.currentTimeMillis()) {

    /**
     * Get elapsed time in milliseconds since timer was created
     */
    def elapsedMs: Long = System.currentTimeMillis() - startTime

    /**
     * Create a step timer for measuring individual steps
     */
    def stepTimer(): Timer = Timer()
  }

  /**
   * Benchmark execution result containing timing information
   */
  case class BenchmarkResult[T](
    result: T,
    durationMs: Long,
    startTime: Long,
    endTime: Long
  ) {
    def timestamp: Long = endTime
  }

  /**
   * Execute a function and measure its execution time
   *
   * @param operation The operation to benchmark
   * @tparam T The return type of the operation
   * @return BenchmarkResult containing the result and timing information
   */
  def time[T](operation: => T): BenchmarkResult[T] = {
    val startTime = System.currentTimeMillis()
    val result    = operation
    val endTime   = System.currentTimeMillis()

    BenchmarkResult(
      result = result,
      durationMs = endTime - startTime,
      startTime = startTime,
      endTime = endTime
    )
  }

  /**
   * Execute a function with step-by-step timing
   *
   * @param operation The operation to benchmark with step tracking
   * @tparam T The return type of the operation
   * @return BenchmarkResult containing the result and timing information
   */
  def timeWithSteps[T](operation: Timer => T): BenchmarkResult[T] = {
    val timer   = Timer()
    val result  = operation(timer)
    val endTime = System.currentTimeMillis()

    BenchmarkResult(
      result = result,
      durationMs = timer.elapsedMs,
      startTime = timer.startTime,
      endTime = endTime
    )
  }

  /**
   * Create a current timestamp for tracing events
   */
  def currentTimestamp: Long = System.currentTimeMillis()
}
