package org.llm4s.javaapi

import org.llm4s.agent.AgentResult
import org.llm4s.agent.graph.StreamEvent

/**
 * Receives an agent turn's events from [[JAgent.stream]], [[JAgent.streamResume]] or
 * [[JAgent.streamRecover]]: each event in order through [[onEvent]], then exactly one of
 * [[onComplete]] (the turn's result) or [[onError]] (the error it failed with). Every call is made on
 * the stream's own thread - never the caller's, and never the runtime's - one at a time.
 *
 * Only [[onEvent]] is abstract, so a Java lambda is a listener:
 * {{{
 * LlmResult<AgentStream> started = agent.stream(threadId, "Explain monads", event ->
 *     StreamEvents.decode(AgentEvents.TextDelta(), event).ifPresent(d -> System.out.print(d.text())));
 * LlmResult<AgentResult> result = started.get().await();
 * }}}
 *
 * A listener slower than the stream never holds the turn up: live events (text deltas, tool
 * progress) that do not fit in the stream's buffer are dropped, and the listener receives one
 * `StreamEvent.LiveGap` with their count where they were dropped. Durable events are never dropped.
 * A listener that throws from [[onEvent]] cancels the turn, and [[onError]] receives what it threw; one
 * that sets its own thread's interrupt flag cancels it too, at once, and receives a `CancelledError`.
 */
trait AgentStreamListener {

  /** One event of the turn: a `StreamEvent.Durable`, a `StreamEvent.Live`, or a `StreamEvent.LiveGap`. */
  def onEvent(event: StreamEvent): Unit

  /** The turn's result, after its last event. Called at most once, and never together with [[onError]]. */
  def onComplete(result: AgentResult): Unit = ()

  /**
   * The error the turn failed with - or, after [[AgentStream.cancel]], the cancellation - after its
   * last delivered event. Called at most once, and never together with [[onComplete]].
   */
  def onError(error: LlmException): Unit = ()
}
