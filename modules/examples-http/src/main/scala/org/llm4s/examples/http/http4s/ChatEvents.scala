package org.llm4s.examples.http.http4s

import cats.effect.IO
import fs2.Stream
import org.http4s.ServerSentEvent
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.effect.cats.{ AgentIO, AgentStreamItem, LLMException }
import org.llm4s.error.{ LLMError, UnknownError }
import org.llm4s.examples.http.{ ChatProtocol, HttpErrors }

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/**
 * The run of `POST /chat/stream` as server-sent events, shared by the http4s app ([[ChatRoutes]]) and the Tapir app
 * served on http4s ([[org.llm4s.examples.http.tapir.TapirChatRoutes TapirChatRoutes]]).
 */
object ChatEvents {

  /**
   * `AgentIO.stream` of `message` on a new thread as server-sent events (see
   * [[org.llm4s.examples.http.ChatProtocol ChatProtocol]]), with a `keep-alive` comment every `heartbeat` until the
   * run's own events end. Interrupting the stream - as a failed write to a client that has gone away does - cancels
   * the run.
   */
  def apply(agent: AgentIO[IO], message: String, heartbeat: FiniteDuration): Stream[IO, ServerSentEvent] = {
    val run = agent
      .stream(ThreadId(UUID.randomUUID().toString), message)
      .collect {
        case AgentStreamItem.Event(AgentEvents.TextDelta(d)) =>
          event("delta", ChatProtocol.delta(d.attempt, d.text))
        case AgentStreamItem.Event(AgentEvents.ToolCallStarted(t)) =>
          event("tool_call", ChatProtocol.toolCall(t.toolCallId, t.tool))
        case AgentStreamItem.Done(result) =>
          event("done", ChatProtocol.result(result))
      }
      .handleErrorWith(error => Stream.emit(event("error", ChatProtocol.streamError(HttpErrors.of(llmError(error))))))
    val keepAlive = Stream.awakeEvery[IO](heartbeat).as(ServerSentEvent(comment = Some("keep-alive")))
    run.mergeHaltL(keepAlive)
  }

  /** The `LLMError` an effect failed with: `AgentIO` raises it as an `LLMException`; anything else is unexpected. */
  def llmError(error: Throwable): LLMError = error match {
    case e: LLMException => e.error
    case other           => UnknownError(Option(other.getMessage).getOrElse(other.getClass.getName), other)
  }

  private def event(name: String, data: ujson.Value): ServerSentEvent =
    ServerSentEvent(data = Some(data.render()), eventType = Some(name))
}
