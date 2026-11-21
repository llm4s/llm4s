package org.llm4s.examples.http.playframework

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.Source
import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.error.{ LLMError, UnknownError }
import org.llm4s.examples.http.{ ChatProtocol, HttpErrors }
import org.llm4s.pekko.{ AgentPekko, AgentStreamItem, LLMException }

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

/** The run of `POST /chat/stream` in the Play app, as the text of server-sent events. */
object PlayChatEvents {

  /** The comment sent while a run is quiet. */
  val KeepAlive: String = ": keep-alive\n\n"

  /**
   * `AgentPekko.stream` of `message` on a new thread as server-sent events (see
   * [[org.llm4s.examples.http.ChatProtocol ChatProtocol]]), with a [[KeepAlive]] comment every `heartbeat` until the
   * run's own events end. Cancelling the source - as Play's server does when the client has gone away - cancels the
   * run, which interrupts its provider call.
   */
  def apply(agent: AgentPekko, message: String, heartbeat: FiniteDuration): Source[String, NotUsed] = {
    val run = agent
      .stream(ThreadId(UUID.randomUUID().toString), message)
      .collect {
        case AgentStreamItem.Event(AgentEvents.TextDelta(d)) => event("delta", ChatProtocol.delta(d.attempt, d.text))
        case AgentStreamItem.Event(AgentEvents.ToolCallStarted(t)) =>
          event("tool_call", ChatProtocol.toolCall(t.toolCallId, t.tool))
        case AgentStreamItem.Done(result) => event("done", ChatProtocol.result(result))
      }
      .recover { case error => event("error", ChatProtocol.streamError(HttpErrors.of(llmError(error)))) }
    run
      .merge(Source.tick(heartbeat, heartbeat, KeepAlive), eagerComplete = true)
      .mapMaterializedValue(_ => NotUsed)
  }

  /** The `LLMError` a stream or `Future` failed with: `llm4s-pekko` carries it in an `LLMException`. */
  def llmError(error: Throwable): LLMError = error match {
    case e: LLMException => e.error
    case other           => UnknownError(Option(other.getMessage).getOrElse(other.getClass.getName), other)
  }

  /** One server-sent event: its name, then its data on one line (`render()` writes JSON without newlines). */
  def event(name: String, data: ujson.Value): String = s"event: $name\ndata: ${data.render()}\n\n"
}
