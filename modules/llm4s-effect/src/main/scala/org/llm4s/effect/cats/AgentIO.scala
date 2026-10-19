package org.llm4s.effect.cats

import cats.effect.kernel.{ Async, Resource }
import cats.syntax.flatMap.*
import cats.syntax.functor.*
import fs2.Stream
import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ EventHandlers, EventType, InterruptId, RunConfig, RunStatus, StreamEvent, ThreadId }
import org.llm4s.types.Result

/**
 * cats-effect wrapper for [[org.llm4s.agent.Agent Agent]].
 *
 * Lifts every `Result[AgentResult]` into `F[AgentResult]`, raising an `LLMError` as
 * [[LLMException]] in the error channel. `run` and `continueConversation` start the turn on a
 * blocking thread and then await it, so cancelling the fiber cancels the turn itself
 * ([[org.llm4s.agent.AgentRun.cancel AgentRun.cancel]]) and returns once it has ended: its model call and tool calls are interrupted, and the thread is left for
 * `recover`. `recover` and `resume` do the same through `Agent.startRecover` and `Agent.startResume`.
 *
 * `stream`, `streamResume` and `streamRecover` run a turn as an fs2 stream of its events
 * ([[AgentStreamItem.Event]]), then its result ([[AgentStreamItem.Done]]); interrupting the stream
 * cancels the turn as cancelling `run` does. Stopping early (`take(n)`) also cancels the turn. A
 * consumer too slow for the stream's buffer never holds the run up: it loses live events (text
 * deltas, tool progress) and receives one `StreamEvent.LiveGap` with their count where they were
 * dropped; durable events are kept, and the run carries on. A consumer that stops draining
 * altogether fails the stream (and so cancels the turn) once more than `Agent.StreamCapacity` durable
 * events wait, as the runtime disconnects a listener that far behind.
 *
 * A model call that throws instead of returning `Left` ends the turn with a
 * `GraphError.NodeFailed` whose `cause` is the `LLMError` the runtime made of the throwable; it is
 * raised as an [[LLMException]], not as the raw throwable.
 *
 * Intentionally a thin wrapper: build the [[org.llm4s.agent.Agent Agent]] with its tools, middleware (guardrails),
 * handoffs and options through `Agent.builder`, or `LLMClientIO.agent`.
 */
trait AgentIO[F[_]] {

  /** One turn on a new thread; see [[org.llm4s.agent.Agent.run Agent.run]]. */
  def run(query: String, config: RunConfig = RunConfig()): F[AgentResult]

  /** The next turn on `previous`'s thread; see [[org.llm4s.agent.Agent.continueConversation Agent.continueConversation]]. */
  def continueConversation(previous: AgentResult, query: String, config: RunConfig = RunConfig()): F[AgentResult]

  /** Continues `threadId`'s failed or interrupted run; see [[org.llm4s.agent.Agent.recover Agent.recover]]. */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig()): F[AgentResult]

  /** Answers pending approvals and questions and continues; see [[org.llm4s.agent.Agent.resume Agent.resume]]. */
  def resume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig()
  ): F[AgentResult]

  /**
   * One turn on `threadId`, as a stream: every event of the turn ([[org.llm4s.agent.Agent.stream Agent.stream]]), then
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

  /**
   * The events of `run` that `eventType` names - durable and live - decoded: `events` with
   * `EventHandlers.on(eventType)(identity)`; see the overload taking handlers.
   */
  def events[A](run: AgentRun, eventType: EventType[A]): Stream[F, A]

  /**
   * What `handlers` make of `run`'s events: one element for each event a handler takes, in delivery
   * order. It observes the run, through [[org.llm4s.agent.AgentRun.subscribe AgentRun.subscribe]]: it replays the run's durable events
   * from its start and then follows it live, but misses the live events sent before the stream
   * started - use [[stream]] to see every one. It ends after the run's terminal event, or once a run
   * that ends without one has delivered its last event; the run's own outcome is not raised - await
   * the run for it. Ending the stream early, or interrupting it, cancels its subscription only: the
   * run carries on.
   *
   * Handlers run on the stream, not on the subscription: a slow or throwing handler slows or fails
   * the stream, never the run. A consumer too slow for the stream's buffer loses live events, as
   * [[stream]]'s does - a `LiveGap`, which a handler sees through `otherwise`. A subscription that
   * disconnects, or a consumer that falls more than `Agent.StreamCapacity` durable events behind,
   * raises [[LLMException]].
   */
  def events[R](run: AgentRun, handlers: EventHandlers[R]): Stream[F, R]
}

object AgentIO {

  /**
   * Events buffered between the turn's subscription and the stream's consumer. Unlike
   * [[LLMClientIO]]'s buffer, this one is lossy - on overflow it drops live events and emits a
   * `LiveGap` rather than applying backpressure - so a larger size means fewer gaps for a slow
   * consumer, not just less stalling.
   */
  private val StreamBufferSize = 256

  /** Wraps an already-constructed [[org.llm4s.agent.Agent Agent]]. */
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

    def events[A](run: AgentRun, eventType: EventType[A]): Stream[F, A] =
      events(run, EventHandlers.on(eventType)(identity))

    /**
     * Subscribes a buffer's listener to `run`, ending the buffer when the subscription ends; the
     * release cancels the subscription - never the run - and closes the buffer.
     */
    def events[R](run: AgentRun, handlers: EventHandlers[R]): Stream[F, R] = {
      val acquire = F
        .blocking {
          val buffer = AgentEventBuffer(StreamBufferSize)
          run.subscribeEnding(Agent.StreamCapacity, () => buffer.end())(buffer.listener).map(s => (buffer, s))
        }
        .flatMap(raised)
      Stream
        .bracket(acquire) { case (buffer, subscription) =>
          F.blocking {
            subscription.cancel()
            buffer.close()
          }
        }
        .flatMap { case (buffer, _) =>
          Stream
            .repeatEval(F.interruptible(buffer.take()).flatMap(raised))
            .unNoneTerminate
            .evalMapFilter(event => F.delay(handlers.dispatch(event)))
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
