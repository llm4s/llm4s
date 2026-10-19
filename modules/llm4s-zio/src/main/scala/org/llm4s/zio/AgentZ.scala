package org.llm4s.zio

import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ EventHandlers, EventType, InterruptId, RunConfig, RunStatus, StreamEvent, ThreadId }
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
 * were dropped; durable events are kept, and the run carries on. A consumer that stops draining
 * altogether fails the stream (and so cancels the turn) once more than `Agent.StreamCapacity` durable
 * events wait, as the runtime disconnects a listener that far behind.
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

  /**
   * The events of `run` that `eventType` names - durable and live - decoded: `events` with
   * `EventHandlers.on(eventType)(identity)`; see the overload taking handlers.
   */
  def events[A](run: AgentRun, eventType: EventType[A]): ZStream[Any, LLMError, A]

  /**
   * What `handlers` make of `run`'s events: one element for each event a handler takes, in delivery
   * order. It observes the run, through [[AgentRun.subscribe]]: it replays the run's durable events
   * from its start and then follows it live, but misses the live events sent before the stream
   * started - use [[stream]] to see every one. It ends after the run's terminal event, or once a run
   * that ends without one has delivered its last event; the run's own outcome is not failed with -
   * await the run for it. Ending the stream early, or interrupting it, cancels its subscription only:
   * the run carries on.
   *
   * Handlers run on the stream, not on the subscription: a slow handler slows the stream, never the
   * run, and one that throws dies the stream (a defect). A consumer too slow for the stream's buffer
   * loses live events, as [[stream]]'s does - a `LiveGap`, which a handler sees through `otherwise`.
   * A subscription that disconnects, or a consumer that falls more than `Agent.StreamCapacity`
   * durable events behind, fails the stream with an `LLMError`.
   */
  def events[R](run: AgentRun, handlers: EventHandlers[R]): ZStream[Any, LLMError, R]

  /**
   * [[events]], with the events of every child `run` delegates to (design 4.47): the subscription is made with
   * `nested = true`, so `handlers` also see each `StreamEvent.Nested` - matched with `EventHandlers.onChild`, or
   * `otherwise` - after the parent's `SubagentEvents.TaskStarted` for its child. Nested events are live: a consumer too
   * slow for the buffer loses them as a `LiveGap`.
   */
  def nestedEvents[R](run: AgentRun, handlers: EventHandlers[R]): ZStream[Any, LLMError, R]
}

object AgentZ {

  /**
   * Events buffered between the turn's subscription and the stream's consumer. Unlike
   * [[LLMClientZ]]'s buffer, this one is lossy - on overflow it drops live events and emits a
   * `LiveGap` rather than applying backpressure - so a larger size means fewer gaps for a slow
   * consumer, not just less stalling.
   */
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

    def events[A](run: AgentRun, eventType: EventType[A]): ZStream[Any, LLMError, A] =
      events(run, EventHandlers.on(eventType)(identity))

    /**
     * Subscribes a buffer's listener to `run`, ending the buffer when the subscription ends; the
     * release cancels the subscription - never the run - and closes the buffer.
     */
    def events[R](run: AgentRun, handlers: EventHandlers[R]): ZStream[Any, LLMError, R] =
      observed(run, handlers, nested = false)

    def nestedEvents[R](run: AgentRun, handlers: EventHandlers[R]): ZStream[Any, LLMError, R] =
      observed(run, handlers, nested = true)

    private def observed[R](run: AgentRun, handlers: EventHandlers[R], nested: Boolean): ZStream[Any, LLMError, R] = {
      val acquire =
        ZIO
          .attemptBlocking {
            val buffer = AgentEventBuffer(StreamBufferSize)
            run
              .subscribeEnding(Agent.StreamCapacity, () => buffer.end(), nested)(buffer.listener)
              .map(s => (buffer, s))
          }
          .orDie
          .flatMap(ZIO.fromEither(_))
      ZStream
        .acquireReleaseWith(acquire) { case (buffer, subscription) =>
          ZIO.attemptBlocking {
            subscription.cancel()
            buffer.close()
          }.ignore
        }
        .flatMap { case (buffer, _) =>
          ZStream
            .repeatZIOOption(
              ZIO.attemptBlockingInterrupt(buffer.take()).orDie.flatMap {
                case Right(Some(event)) => ZIO.succeed(event)
                case Right(None)        => ZIO.fail(None)
                case Left(error)        => ZIO.fail(Some(error))
              }
            )
            .mapZIO(event => ZIO.succeed(handlers.dispatch(event)))
            .collectSome
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
