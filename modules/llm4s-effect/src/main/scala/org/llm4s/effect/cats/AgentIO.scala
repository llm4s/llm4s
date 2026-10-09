package org.llm4s.effect.cats

import cats.effect.kernel.{ Async, Resource }
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import fs2.Stream
import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, RunStatus, StreamEvent, ThreadId }
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
 * `stream`, `streamResume` and `streamRecover` run a turn as an fs2 stream of its events
 * ([[AgentStreamItem.Event]]), then its result ([[AgentStreamItem.Done]]); interrupting the stream
 * cancels the turn as cancelling `run` does. Stopping early (`take(n)`) also cancels the turn. A
 * consumer too slow for the stream's buffer never holds the run up: it loses live events (text
 * deltas, tool progress) and receives one `StreamEvent.LiveGap` with their count where they were
 * dropped; durable events are never dropped, and the run carries on.
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

  /**
   * One turn on `threadId`, as a stream: every event of the turn ([[Agent.stream]]), then
   * `Done(result)`. A refused start or a failed turn raises [[LLMException]]; so does a subscription
   * that disconnects (its listener failed). A slow consumer loses live events and gets a
   * `StreamEvent.LiveGap` instead; it does not cancel the run. Interrupting the stream cancels the
   * turn and returns once it has ended, leaving the thread for `recover`.
   */
  def stream(threadId: ThreadId, query: String, config: RunConfig = RunConfig()): Stream[F, AgentStreamItem]

  /** [[resume]] as a stream; see [[stream]]. */
  def streamResume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): Stream[F, AgentStreamItem]

  /** [[recover]] as a stream; see [[stream]]. */
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig()): Stream[F, AgentStreamItem]
}

object AgentIO {

  /** Events buffered between the turn's subscription and the stream's consumer. */
  private val StreamBufferSize = 256

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

    def stream(threadId: ThreadId, query: String, config: RunConfig): Stream[F, AgentStreamItem] =
      streaming((onEnd, listener) => agent.streamEnding(threadId, query, config, Nil, onEnd)(listener))

    def streamResume(
      threadId: ThreadId,
      answers: Map[InterruptId, ujson.Value],
      config: RunConfig
    ): Stream[F, AgentStreamItem] =
      streaming((onEnd, listener) => agent.streamResumeEnding(threadId, answers, config, onEnd)(listener))

    def streamRecover(threadId: ThreadId, config: RunConfig): Stream[F, AgentStreamItem] =
      streaming((onEnd, listener) => agent.streamRecoverEnding(threadId, config, onEnd)(listener))

    /**
     * Starts the turn with a buffer's listener, ending the buffer when the turn's subscription ends -
     * so the stream ends even for a turn that commits no terminal event. The start is uncancelable,
     * so a turn is never started and forgotten; the release cancels the turn if it is still running,
     * awaits its end - without waiting on the buffer's listener, which never blocks - and closes the
     * buffer.
     */
    private def streaming(
      start: (() => Unit, StreamEvent => Unit) => Result[AgentRun]
    ): Stream[F, AgentStreamItem] = {
      val acquire = F
        .blocking {
          val buffer = AgentEventBuffer(StreamBufferSize)
          start(() => buffer.end(), buffer.listener).map(run => (buffer, run))
        }
        .flatMap(raised)
      Stream
        .bracketCase(acquire) { case ((buffer, run), exit) =>
          exit match {
            // the stream emitted Done, so the turn has ended
            case Resource.ExitCase.Succeeded if run.status != RunStatus.Running =>
              F.delay(buffer.close())
            // interrupted, failed, or ended early by the consumer (`take(n)`): the turn may be running
            case _ =>
              F.blocking {
                run.cancel()
                run.await()
                buffer.close()
              }.void
          }
        }
        .flatMap { case (buffer, run) =>
          Stream
            .repeatEval(F.interruptible(buffer.take()).flatMap(raised))
            .unNoneTerminate
            .map(AgentStreamItem.Event(_))
            .append(Stream.eval(F.interruptible(run.await()).flatMap(raised)).map(AgentStreamItem.Done(_)))
        }
    }

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
