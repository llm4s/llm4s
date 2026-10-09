package org.llm4s.pekko

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.llm4s.agent.{ Agent, AgentEventBuffer, AgentResult, AgentRun }
import org.llm4s.agent.graph.{ InterruptId, RunConfig, RunStatus, StreamEvent, ThreadId }
import org.llm4s.error.LLMError
import org.llm4s.types.Result

import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }
import scala.annotation.tailrec
import scala.concurrent.{ blocking, ExecutionContext, Future }

/**
 * Apache Pekko Streams wrapper for [[Agent]], the Pekko counterpart of `AgentIO` (cats-effect, fs2) and
 * `AgentZ` (ZIO).
 *
 * `stream`, `streamResume` and `streamRecover` run a turn as a `Source` of its events
 * ([[AgentStreamItem.Event]]), then its result ([[AgentStreamItem.Done]]). The turn is started when the
 * stream is materialized, once per materialization. Cancelling the stream - or stopping it early with
 * `take` - cancels the turn: its model call and tool calls are interrupted and the thread is left for
 * `recover`. A consumer too slow for the stream's buffer never holds the run up: it loses live events (text
 * deltas, tool progress) and receives one `StreamEvent.LiveGap` with their count where they were dropped;
 * durable events are never dropped, and the run carries on.
 *
 * A refused start (a blank query, a busy thread), a failed turn and a subscription that disconnects all
 * fail the stream with an [[LLMException]]. A turn that commits no terminal event still ends the stream.
 *
 * `run`, `continueConversation`, `recover` and `resume` return a `Future`, started and awaited on the
 * `ExecutionContext` you pass (a blocking one: the wait blocks a thread). A `Future` cannot be cancelled;
 * stream the turn when it has to be cancellable.
 *
 * Intentionally a thin wrapper: build the [[Agent]] with its tools, middleware (guardrails), handoffs and
 * options through `Agent.builder`, or [[LLMClientPekko.agent]].
 */
trait AgentPekko {

  /** One turn on a new thread; see [[Agent.run]]. */
  def run(query: String, config: RunConfig = RunConfig())(using ec: ExecutionContext): Future[AgentResult]

  /** The next turn on `previous`'s thread; see [[Agent.continueConversation]]. */
  def continueConversation(previous: AgentResult, query: String, config: RunConfig = RunConfig())(using
    ec: ExecutionContext
  ): Future[AgentResult]

  /** Continues `threadId`'s failed or interrupted run; see [[Agent.recover]]. */
  def recover(threadId: ThreadId, config: RunConfig = RunConfig())(using ec: ExecutionContext): Future[AgentResult]

  /** Answers pending approvals and questions and continues; see [[Agent.resume]]. */
  def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig = RunConfig())(using
    ec: ExecutionContext
  ): Future[AgentResult]

  /**
   * One turn on `threadId`, as a stream: every event of the turn ([[Agent.stream]]), then `Done(result)`.
   * `bufferSize` is how many live events wait for a slow consumer before the rest are counted into a
   * `LiveGap`; a value below 1 fails the stream at once.
   */
  def stream(
    threadId: ThreadId,
    query: String,
    config: RunConfig = RunConfig(),
    bufferSize: Int = AgentPekko.DefaultBufferSize
  ): Source[AgentStreamItem, NotUsed]

  /** [[resume]] as a stream; see [[stream]]. */
  def streamResume(
    threadId: ThreadId,
    answers: Map[InterruptId, ujson.Value],
    config: RunConfig = RunConfig(),
    bufferSize: Int = AgentPekko.DefaultBufferSize
  ): Source[AgentStreamItem, NotUsed]

  /** [[recover]] as a stream; see [[stream]]. */
  def streamRecover(
    threadId: ThreadId,
    config: RunConfig = RunConfig(),
    bufferSize: Int = AgentPekko.DefaultBufferSize
  ): Source[AgentStreamItem, NotUsed]
}

object AgentPekko {

  /** Live events buffered between a turn's subscription and the stream's consumer. */
  val DefaultBufferSize: Int = 256

  /** Items handed over between the pump thread and the stream, on top of the live-event buffer. */
  private val HandOver = 16

  /** Wraps an already-constructed [[Agent]]. */
  def apply(agent: Agent): AgentPekko = new Impl(agent)

  final private class Impl(agent: Agent) extends AgentPekko {

    def run(query: String, config: RunConfig)(using ec: ExecutionContext): Future[AgentResult] =
      awaiting(agent.start(ThreadId(java.util.UUID.randomUUID().toString), query, config))

    def continueConversation(previous: AgentResult, query: String, config: RunConfig)(using
      ec: ExecutionContext
    ): Future[AgentResult] =
      awaiting(agent.start(previous.threadId, query, config))

    def recover(threadId: ThreadId, config: RunConfig)(using ec: ExecutionContext): Future[AgentResult] =
      awaiting(agent.startRecover(threadId, config))

    def resume(threadId: ThreadId, answers: Map[InterruptId, ujson.Value], config: RunConfig)(using
      ec: ExecutionContext
    ): Future[AgentResult] =
      awaiting(agent.startResume(threadId, answers, config))

    def stream(
      threadId: ThreadId,
      query: String,
      config: RunConfig,
      bufferSize: Int
    ): Source[AgentStreamItem, NotUsed] =
      streaming(bufferSize)((onEnd, listener) => agent.streamEnding(threadId, query, config, Nil, onEnd)(listener))

    def streamResume(
      threadId: ThreadId,
      answers: Map[InterruptId, ujson.Value],
      config: RunConfig,
      bufferSize: Int
    ): Source[AgentStreamItem, NotUsed] =
      streaming(bufferSize)((onEnd, listener) => agent.streamResumeEnding(threadId, answers, config, onEnd)(listener))

    def streamRecover(threadId: ThreadId, config: RunConfig, bufferSize: Int): Source[AgentStreamItem, NotUsed] =
      streaming(bufferSize)((onEnd, listener) => agent.streamRecoverEnding(threadId, config, onEnd)(listener))

    /**
     * Starts the turn with a buffer's listener, ending the buffer when the turn's subscription ends - so the
     * stream ends even for a turn that commits no terminal event.
     */
    private def streaming(bufferSize: Int)(
      start: (() => Unit, StreamEvent => Unit) => Result[AgentRun]
    ): Source[AgentStreamItem, NotUsed] =
      BlockingSource("llm4s-pekko-agent", HandOver)(() => new TurnProducer(bufferSize, start))
        .mapMaterializedValue(_ => NotUsed)

    private def awaiting(start: => Result[AgentRun])(using ec: ExecutionContext): Future[AgentResult] =
      Future(blocking(start.flatMap(_.await()))).flatMap {
        case Right(result) => Future.successful(result)
        case Left(error)   => Future.failed(new LLMException(error))
      }
  }

  /**
   * Starts a turn and pumps its events to the stream from this producer's own thread. The turn's event
   * buffer is the only queue that can fill up, and it never blocks the run: it drops live events and
   * counts them into a `LiveGap`.
   */
  final private class TurnProducer(bufferSize: Int, start: (() => Unit, StreamEvent => Unit) => Result[AgentRun])
      extends Producer[AgentStreamItem] {

    private val buffer    = new AtomicReference[AgentEventBuffer](null)
    private val running   = new AtomicReference[AgentRun](null)
    private val cancelled = new AtomicBoolean(false)
    private val completed = new AtomicBoolean(false)

    def run(emitter: Emitter[AgentStreamItem]): Either[LLMError, Unit] =
      if (bufferSize < 1)
        Left(org.llm4s.error.ValidationError("bufferSize", s"must be at least 1, got $bufferSize"))
      else {
        val events = AgentEventBuffer(bufferSize)
        buffer.set(events)
        start(() => events.end(), events.listener).flatMap { agentRun =>
          running.set(agentRun)
          // the stream was cancelled before the turn began
          if (cancelled.get()) {
            agentRun.cancel()
            agentRun.await(): Unit
            events.close()
            Right(())
          } else pump(events, agentRun, emitter)
        }
      }

    @tailrec
    private def pump(
      events: AgentEventBuffer,
      agentRun: AgentRun,
      emitter: Emitter[AgentStreamItem]
    ): Either[LLMError, Unit] =
      events.take() match {
        case Left(error) => Left(error)
        case Right(Some(event)) =>
          emitter.emit(AgentStreamItem.Event(event))
          pump(events, agentRun, emitter)
        case Right(None) =>
          agentRun.await().map { result =>
            emitter.emit(AgentStreamItem.Done(result))
            if (agentRun.status != RunStatus.Running) events.close()
            completed.set(true)
          }
      }

    /**
     * The stream has ended. If the turn did not finish and hand over its result, cancel it, wait for it to
     * end, and close its buffer - on a thread of its own, because the wait blocks.
     */
    def abandon(): Unit = {
      cancelled.set(true)
      if (!completed.get()) {
        val cleanup = new Thread(
          null,
          () => {
            Option(running.get()).foreach { agentRun =>
              agentRun.cancel()
              agentRun.await(): Unit
            }
            Option(buffer.get()).foreach(_.close())
          },
          "llm4s-pekko-agent-cleanup"
        )
        cleanup.setDaemon(true)
        cleanup.start()
      }
    }
  }
}
