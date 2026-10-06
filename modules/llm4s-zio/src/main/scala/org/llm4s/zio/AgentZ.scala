package org.llm4s.zio

import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, RunStatus, StreamEvent, ThreadId }
import org.llm4s.error.LLMError
import org.llm4s.types.Result
import zio.ZIO
import zio.stream.ZStream

/**
 * ZIO wrapper for [[Agent]].
 *
 * Lifts every `Result[AgentResult]` into `ZIO[Any, LLMError, AgentResult]`. `run` and
 * `continueConversation` start the turn on ZIO's blocking pool and then await it, so interrupting
 * the fiber cancels the turn itself ([[AgentRun.cancel]]) and returns once it has ended: its model call and tool calls are
 * interrupted, and the thread is left for `recover`. `recover` and `resume` do the same through
 * `Agent.startRecover` and `Agent.startResume`.
 *
 * `stream`, `streamResume` and `streamRecover` run a turn as a `ZStream` of its events
 * ([[AgentStreamItem.Event]]), then its result ([[AgentStreamItem.Done]]); interrupting the stream
 * cancels the turn as interrupting `run` does. Stopping early (`take(n)`, `runHead`) also cancels the
 * turn. A consumer too slow for the stream's buffer never holds the run up: it loses live events
 * (text deltas, tool progress) and receives one `StreamEvent.LiveGap` with their count where they
 * were dropped; durable events are never dropped, and the run carries on.
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

  /**
   * One turn on `threadId`, as a stream: every event of the turn ([[Agent.stream]]), then
   * `Done(result)`. A refused start or a failed turn fails the stream with the `LLMError`; so does a
   * subscription that disconnects (its listener failed), which also cancels the run. A slow consumer
   * loses live events and gets a `StreamEvent.LiveGap` instead; it does not cancel the run.
   * Interrupting the stream, or stopping it early (`take(n)`, `runHead`), cancels the turn and returns
   * once it has ended, leaving the thread for `recover`.
   */
  def stream(
    threadId: ThreadId,
    query: String,
    config: RunConfig = RunConfig()
  ): ZStream[Any, LLMError, AgentStreamItem]

  /** [[resume]] as a stream; see [[stream]]. */
  def streamResume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): ZStream[Any, LLMError, AgentStreamItem]

  /** [[recover]] as a stream; see [[stream]]. */
  def streamRecover(threadId: ThreadId, config: RunConfig = RunConfig()): ZStream[Any, LLMError, AgentStreamItem]
}

object AgentZ {

  /** Events buffered between the turn's subscription and the stream's consumer. */
  private val StreamBufferSize = 256

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

    def stream(threadId: ThreadId, query: String, config: RunConfig): ZStream[Any, LLMError, AgentStreamItem] =
      streaming((onEnd, listener) => agent.streamEnding(threadId, query, config, Nil, onEnd)(listener))

    def streamResume(
      threadId: ThreadId,
      answers: Map[InterruptId, ujson.Value],
      config: RunConfig
    ): ZStream[Any, LLMError, AgentStreamItem] =
      streaming((onEnd, listener) => agent.streamResumeEnding(threadId, answers, config, onEnd)(listener))

    def streamRecover(threadId: ThreadId, config: RunConfig): ZStream[Any, LLMError, AgentStreamItem] =
      streaming((onEnd, listener) => agent.streamRecoverEnding(threadId, config, onEnd)(listener))

    /**
     * Starts the turn with a buffer's listener, ending the buffer when the turn's subscription ends -
     * so the stream ends even for a turn that commits no terminal event. The acquire is
     * uninterruptible, so a turn is never started and forgotten; the release cancels the turn unless
     * the stream completed and the turn has ended, awaits its end - without waiting on the buffer's
     * listener, which never blocks - and closes the buffer. An early stop (`take(n)`) exits
     * successfully, hence the check on the run's status.
     */
    private def streaming(
      start: (() => Unit, StreamEvent => Unit) => Result[AgentRun]
    ): ZStream[Any, LLMError, AgentStreamItem] = {
      val acquire =
        ZIO
          .attemptBlocking {
            val buffer = AgentEventBuffer(StreamBufferSize)
            start(() => buffer.end(), buffer.listener).map(run => (buffer, run))
          }
          .orDie
          .flatMap(ZIO.fromEither(_))
      ZStream
        .acquireReleaseExitWith(acquire) { case ((buffer, run), exit) =>
          if (exit.isSuccess && run.status != RunStatus.Running) ZIO.succeed(buffer.close())
          else
            ZIO.attemptBlocking {
              run.cancel()
              run.await()
              buffer.close()
            }.ignore
        }
        .flatMap { case (buffer, run) =>
          ZStream
            .repeatZIOOption(
              ZIO.attemptBlockingInterrupt(buffer.take()).orDie.flatMap {
                case Right(Some(event)) => ZIO.succeed(event)
                case Right(None)        => ZIO.fail(None)
                case Left(error)        => ZIO.fail(Some(error))
              }
            )
            .map(AgentStreamItem.Event(_)) ++
            ZStream
              .fromZIO(ZIO.attemptBlockingInterrupt(run.await()).orDie.flatMap(ZIO.fromEither(_)))
              .map(AgentStreamItem.Done(_))
        }
    }

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
