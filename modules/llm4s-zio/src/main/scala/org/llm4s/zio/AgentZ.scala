package org.llm4s.zio

import org.llm4s.agent.{ Agent, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, ThreadId }
import org.llm4s.error.LLMError
import org.llm4s.types.Result
import zio.ZIO

/**
 * ZIO wrapper for [[Agent]].
 *
 * Lifts every `Result[AgentResult]` into `ZIO[Any, LLMError, AgentResult]`. `run` and
 * `continueConversation` start the turn on ZIO's blocking pool and then await it, so interrupting
 * the fiber cancels the turn itself ([[AgentRun.cancel]]) and returns once it has ended: its model call and tool calls are
 * interrupted, and the thread is left for `recover`. `recover` and `resume` do the same through
 * `Agent.startRecover` and `Agent.startResume`.
 *
 * A model call that throws instead of returning `Left` ends the turn with a
 * `GraphError.NodeFailed` whose `cause` is the `LLMError` the runtime made of the throwable; it
 * fails the effect with that error, not as a defect.
 *
 * Intentionally a thin wrapper: build the [[Agent]] with its tools, middleware (guardrails),
 * handoffs and options through `Agent.builder`, or `LLMClientZ.agent`.
 */
trait AgentZ {

  /** One turn on a new thread; see [[Agent.run]]. */
  def run(query: String, config: RunConfig = RunConfig()): ZIO[Any, LLMError, AgentResult]

  /** The next turn on `previous`'s thread; see [[Agent.continueConversation]]. */
  def continueConversation(
    previous: AgentResult,
    query: String,
    config: RunConfig = RunConfig()
  ): ZIO[Any, LLMError, AgentResult]

  /** Continues `threadId`'s failed or interrupted run; see [[Agent.recover]]. */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): ZIO[Any, LLMError, AgentResult]

  /** Answers pending approvals and questions and continues; see [[Agent.resume]]. */
  def resume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): ZIO[Any, LLMError, AgentResult]
}

object AgentZ {

  /** Wraps an already-constructed [[Agent]]. */
  def apply(agent: Agent): AgentZ = new Impl(agent)

  final private class Impl(agent: Agent) extends AgentZ {

    def run(query: String, config: RunConfig): ZIO[Any, LLMError, AgentResult] =
      awaiting(agent.start(ThreadId(java.util.UUID.randomUUID().toString), query, config))

    def continueConversation(previous: AgentResult, query: String, config: RunConfig): ZIO[Any, LLMError, AgentResult] =
      awaiting(agent.start(previous.threadId, query, config))

    def recover(threadId: ThreadId, config: RunConfig): ZIO[Any, LLMError, AgentResult] =
      awaiting(agent.startRecover(threadId, config))

    def resume(
      threadId: ThreadId,
      answers: Map[InterruptId, ujson.Value],
      config: RunConfig
    ): ZIO[Any, LLMError, AgentResult] =
      awaiting(agent.startResume(threadId, answers, config))

    /**
     * Starts the turn and awaits it. The start is uninterruptible, so a turn is never started and
     * forgotten; the wait is interruptible, and an interrupted wait cancels the turn.
     */
    private def awaiting(start: => Result[AgentRun]): ZIO[Any, LLMError, AgentResult] =
      ZIO.uninterruptibleMask { restore =>
        ZIO.attemptBlocking(start).orDie.flatMap(ZIO.fromEither(_)).flatMap { run =>
          restore(ZIO.attemptBlockingInterrupt(run.await()).orDie.flatMap(ZIO.fromEither(_)))
            .onInterrupt(
              // Interruption returns once the turn has ended, so its thread can be recovered at once.
              ZIO.attemptBlocking {
                run.cancel()
                run.await()
              }.ignore
            )
        }
      }
  }
}
