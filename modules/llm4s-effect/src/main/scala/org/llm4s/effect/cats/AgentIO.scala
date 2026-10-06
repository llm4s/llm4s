package org.llm4s.effect.cats

import cats.effect.kernel.Async
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import org.llm4s.agent.{ Agent, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, ThreadId }
import org.llm4s.types.Result

/**
 * cats-effect wrapper for [[Agent]].
 *
 * Lifts every `Result[AgentResult]` into `F[AgentResult]`, raising an `LLMError` as
 * [[LLMException]] in the error channel. `run` and `continueConversation` start the turn on a
 * blocking thread and then await it, so cancelling the fiber cancels the turn itself
 * ([[AgentRun.cancel]]) and returns once it has ended: its model call and tool calls are interrupted, and the thread is left for
 * `recover`. `recover` and `resume` do the same through `Agent.startRecover` and `Agent.startResume`.
 *
 * A model call that throws instead of returning `Left` ends the turn with a
 * `GraphError.NodeFailed` whose `cause` is the `LLMError` the runtime made of the throwable; it is
 * raised as an [[LLMException]], not as the raw throwable.
 *
 * Intentionally a thin wrapper: build the [[Agent]] with its tools, middleware (guardrails),
 * handoffs and options through `Agent.builder`, or `LLMClientIO.agent`.
 */
trait AgentIO[F[_]] {

  /** One turn on a new thread; see [[Agent.run]]. */
  def run(query: String, config: RunConfig = RunConfig()): F[AgentResult]

  /** The next turn on `previous`'s thread; see [[Agent.continueConversation]]. */
  def continueConversation(previous: AgentResult, query: String, config: RunConfig = RunConfig()): F[AgentResult]

  /** Continues `threadId`'s failed or interrupted run; see [[Agent.recover]]. */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): F[AgentResult]

  /** Answers pending approvals and questions and continues; see [[Agent.resume]]. */
  def resume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): F[AgentResult]
}

object AgentIO {

  /** Wraps an already-constructed [[Agent]]. */
  def apply[F[_]: Async](agent: Agent): AgentIO[F] = new Impl[F](agent)

  final private class Impl[F[_]](agent: Agent)(using F: Async[F]) extends AgentIO[F] {

    def run(query: String, config: RunConfig): F[AgentResult] =
      awaiting(agent.start(ThreadId(java.util.UUID.randomUUID().toString), query, config))

    def continueConversation(previous: AgentResult, query: String, config: RunConfig): F[AgentResult] =
      awaiting(agent.start(previous.threadId, query, config))

    def recover(threadId: ThreadId, config: RunConfig): F[AgentResult] =
      awaiting(agent.startRecover(threadId, config))

    def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig): F[AgentResult] =
      awaiting(agent.startResume(threadId, answers, config))

    private def raised[A](result: Result[A]): F[A] =
      result match {
        case Right(a) => F.pure(a)
        case Left(e)  => F.raiseError(new LLMException(e))
      }

    /**
     * Starts the turn and awaits it. The start is uncancelable, so a turn is never started and
     * forgotten; the wait is interruptible, and a cancelled wait cancels the turn.
     */
    private def awaiting(start: => Result[AgentRun]): F[AgentResult] =
      F.uncancelable { poll =>
        F.blocking(start).flatMap(raised).flatMap { run =>
          F.onCancel(
            poll(F.interruptible(run.await()).flatMap(raised)),
            // Cancelling returns once the turn has ended, so its thread can be recovered at once.
            F.blocking {
              run.cancel()
              run.await()
            }.void
          )
        }
      }
  }
}
