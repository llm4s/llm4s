package org.llm4s.toolapi

import org.llm4s.error.ValidationError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Tests for ToolExecutionStrategy sealed trait and all strategy types
 */
class ToolExecutionStrategySpec extends AnyFlatSpec with Matchers {

  // ============ Sequential Strategy ============

  "ToolExecutionStrategy.Sequential" should "be a valid strategy" in {
    val strategy = ToolExecutionStrategy.Sequential

    strategy shouldBe a[ToolExecutionStrategy]
  }

  // ============ Parallel Strategy ============

  "ToolExecutionStrategy.Parallel" should "be a valid strategy" in {
    val strategy = ToolExecutionStrategy.Parallel

    strategy shouldBe a[ToolExecutionStrategy]
  }

  // ============ ParallelWithLimit Strategy ============

  "ToolExecutionStrategy.ParallelWithLimit" should "accept positive concurrency limit" in {
    val strategy = ToolExecutionStrategy.parallelWithLimit(5).toOption.get

    strategy.maxConcurrency shouldBe 5
    strategy shouldBe a[ToolExecutionStrategy]
  }

  it should "accept concurrency limit of 1" in {
    val strategy = ToolExecutionStrategy.parallelWithLimit(1).toOption.get

    strategy.maxConcurrency shouldBe 1
  }

  it should "reject zero concurrency limit as a ValidationError, not a throw" in {
    val error = ToolExecutionStrategy.parallelWithLimit(0).left.toOption.get
    error shouldBe a[ValidationError]
    error.message should (include("maxConcurrency").and(include("0")))
  }

  it should "reject negative concurrency limit" in {
    ToolExecutionStrategy.parallelWithLimit(-1).left.toOption.get shouldBe a[ValidationError]
  }

  it should "have no public constructor that skips the check" in {
    assertTypeError("ToolExecutionStrategy.ParallelWithLimit(0)")
    assertTypeError("new ToolExecutionStrategy.ParallelWithLimit(0)")
    assertTypeError("ToolExecutionStrategy.parallelWithLimit(2).map(_.copy(maxConcurrency = 0))")
  }

  // ============ Default Strategy ============

  "ToolExecutionStrategy.default" should "be Sequential" in {
    ToolExecutionStrategy.default shouldBe ToolExecutionStrategy.Sequential
  }

  // ============ Pattern Matching ============

  "ToolExecutionStrategy" should "support exhaustive pattern matching" in {
    def describeStrategy(strategy: ToolExecutionStrategy): String = strategy match {
      case ToolExecutionStrategy.Sequential               => "sequential"
      case ToolExecutionStrategy.Parallel                 => "parallel"
      case ToolExecutionStrategy.ParallelWithLimit(limit) => s"parallel-$limit"
    }

    describeStrategy(ToolExecutionStrategy.Sequential) shouldBe "sequential"
    describeStrategy(ToolExecutionStrategy.Parallel) shouldBe "parallel"
    describeStrategy(ToolExecutionStrategy.parallelWithLimit(3).toOption.get) shouldBe "parallel-3"
  }
}
