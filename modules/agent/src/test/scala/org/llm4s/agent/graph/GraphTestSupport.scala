package org.llm4s.agent.graph

import org.scalatest.Assertions.fail

import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Random

object GraphTestSupport {

  /** Runs a superstep's tasks last-first, so commit order cannot follow execution order. */
  val reversed: TaskExecutor = new TaskExecutor {
    def runAll[R](tasks: Vector[() => R]): Vector[R] = tasks.reverse.map(_()).reverse
  }

  /** Runs a superstep's tasks concurrently with random delays. */
  def concurrent(seed: Long): TaskExecutor = new TaskExecutor {
    private val random = new Random(seed)
    def runAll[R](tasks: Vector[() => R]): Vector[R] = {
      given ExecutionContext = ExecutionContext.global
      val delays             = tasks.map(_ => random.nextInt(5).toLong)
      val running            = tasks.zip(delays).map((task, delay) => Future { Thread.sleep(delay); task() })
      Await.result(Future.sequence(running), 10.seconds)
    }
  }

  def continue(command: Command): NodeResult = NodeResult.Continue(command)

  extension [O](result: RunResult[O])
    def completed: (ThreadState, O) = result match {
      case RunResult.Completed(state, output, _) => (state, output)
      case RunResult.Failed(_, error)            => fail(s"run failed: ${error.message}")
      case suspended: RunResult.Suspended        => fail(s"run suspended on ${suspended.interrupts.map(_.id.value)}")
    }
    def suspended: RunResult.Suspended = result match {
      case suspended: RunResult.Suspended => suspended
      case other                          => fail(s"run did not suspend: $other")
    }
    def failed: (ThreadState, org.llm4s.error.LLMError) = result match {
      case RunResult.Failed(state, error) => (state, error)
      case completed                      => fail(s"run completed: $completed")
    }
}
