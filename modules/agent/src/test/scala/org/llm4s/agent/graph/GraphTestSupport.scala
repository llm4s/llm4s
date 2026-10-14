package org.llm4s.agent.graph

import org.llm4s.types.Result
import org.scalatest.Assertions.fail

import java.util.concurrent.{ LinkedBlockingQueue, TimeUnit }
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Random

object GraphTestSupport {

  /** How long a test waits for a run to end: a hung run fails the test instead of hanging the build. */
  val RunWait: FiniteDuration = 5.seconds

  /**
   * `handle.await()`, called on another thread so that a run still going after [[RunWait]] fails
   * the test. A refusal or an interrupted await stays a `Left`.
   */
  def awaitResult[O](handle: RunHandle[O]): Result[RunResult[O]] = {
    val done = new LinkedBlockingQueue[Result[RunResult[O]]]()
    Thread.ofVirtual().start(() => done.offer(handle.await()): Unit)
    Option(done.poll(RunWait.toMillis, TimeUnit.MILLISECONDS))
      .getOrElse(fail(s"await did not return within $RunWait"))
  }

  /** The run's result, awaited as [[awaitResult]] does; a `Left` fails the test. */
  def await[O](handle: RunHandle[O]): RunResult[O] =
    awaitResult(handle) match {
      case Right(result) => result
      case Left(error)   => fail(s"await failed: ${error.message}")
    }

  /** Runs `graph` from `input` in a fresh in-memory runtime and returns its result. */
  def runInMemory[I, O](
    graph: CompiledGraph[I, O],
    input: I,
    config: RunConfig = RunConfig(),
    thread: String = "t"
  ): RunResult[O] =
    GraphRuntime.inMemory().start(ThreadId(thread), graph, input, config).flatMap(awaitResult) match {
      case Right(result) => result
      case Left(error)   => fail(s"run refused: ${error.message}")
    }

  /** Steps `execution` to completion, suspension or failure, in memory; no checkpoints, no events. */
  @scala.annotation.tailrec
  def drive[O](
    graph: CompiledGraph[?, O],
    execution: Execution,
    config: RunConfig = RunConfig(),
    thread: String = "t"
  ): RunResult[O] =
    graph.step(ThreadId(thread), execution, config) match {
      case Step.Next(next)   => drive(graph, next, config, thread)
      case Step.Done(result) => result
    }

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

  /** A `RunContext` outside any runtime, for calling hooks and tools directly: events go nowhere. */
  def testRunContext(config: RunConfig = RunConfig()): RunContext =
    new RunContext(
      config,
      RunPosition(ThreadId("t"), config.runId, "", TaskId("task"), NodeId("node"), 0),
      NodeEventSink.none
    )

  /** A `ModelContext` outside any runtime, for calling boundary and model hooks directly, over an empty state. */
  def testModelContext(run: RunContext = testRunContext()): org.llm4s.agent.graph.middleware.ModelContext =
    new org.llm4s.agent.graph.middleware.ModelContext(
      run,
      ThreadState.empty(Map.empty),
      org.llm4s.agent.AgentId.unsafe("test")
    )

  def continue(command: Command): NodeResult = NodeResult.Continue(command)

  extension [O](admitted: org.llm4s.types.Result[RunHandle[O]])
    /** The run's result once it ends, awaited as [[awaitResult]] does; a refusal stays a `Left`. */
    def awaited: Result[RunResult[O]] = admitted.flatMap(awaitResult)

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

/** Forwards every call to `to`; a test overrides what it needs. */
private[graph] class ForwardingCheckpointer(to: Checkpointer) extends Checkpointer {
  def claim(threadId: ThreadId, request: ClaimRequest)                    = to.claim(threadId, request)
  def renew(threadId: ThreadId, token: FencingToken, ttl: FiniteDuration) = to.renew(threadId, token, ttl)
  def release(threadId: ThreadId, token: FencingToken)                    = to.release(threadId, token)
  def commit(threadId: ThreadId, commit: Commit)                          = to.commit(threadId, commit)
  def latest(threadId: ThreadId)                                          = to.latest(threadId)
  def eventsAfter(threadId: ThreadId, afterSeq: Long, limit: Int)         = to.eventsAfter(threadId, afterSeq, limit)
  def compactEvents(threadId: ThreadId, beforeSeq: Long)                  = to.compactEvents(threadId, beforeSeq)
  def deleteThread(threadId: ThreadId, token: FencingToken)               = to.deleteThread(threadId, token)
  def history(threadId: ThreadId, before: Option[String], limit: Int)     = to.history(threadId, before, limit)
  def checkpoint(threadId: ThreadId, checkpointId: String)                = to.checkpoint(threadId, checkpointId)
  def prune(threadId: ThreadId, policy: RetentionPolicy)                  = to.prune(threadId, policy)
  def effect(threadId: ThreadId, key: EffectKey)                          = to.effect(threadId, key)
}
