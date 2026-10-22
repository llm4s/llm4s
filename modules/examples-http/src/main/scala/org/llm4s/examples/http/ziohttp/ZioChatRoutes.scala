package org.llm4s.examples.http.ziohttp

import org.llm4s.agent.events.AgentEvents
import org.llm4s.agent.graph.ThreadId
import org.llm4s.error.{ LLMError, UnknownError }
import org.llm4s.examples.http.{ ChatProtocol, HttpErrors, HttpFailure }
import org.llm4s.zio.{ AgentStreamItem, AgentZ }
import zio.*
import zio.http.*
import zio.stream.ZStream

import java.util.UUID

/**
 * `POST /chat` and `POST /chat/stream` over an [[org.llm4s.zio.AgentZ AgentZ]].
 *
 * `/chat` runs one turn and answers its result as JSON, or the mapped error status ([[HttpErrors.of]]).
 *
 * `/chat/stream` runs the turn as `AgentZ.stream` and sends it as server-sent events (see
 * [[org.llm4s.examples.http.ChatProtocol ChatProtocol]]). The status is `200` once the request is valid, so an error
 * of the run arrives as an `error` event carrying the status it maps to. A comment is sent every `heartbeat`, which
 * keeps proxies from closing a quiet stream and makes a client that has gone away visible to the server. ZIO HTTP
 * interrupts the response stream when its connection closes, and interrupting `AgentZ.stream` cancels the run, which
 * interrupts the provider call.
 */
final class ZioChatRoutes(agent: AgentZ, heartbeat: Duration = ZioChatRoutes.DefaultHeartbeat) {

  val routes: Routes[Any, Nothing] = Routes(
    Method.POST / "chat" -> handler { (request: Request) =>
      withMessage(request) { message =>
        agent
          .run(message)
          .foldCause(
            cause => failed(HttpErrors.of(llmError(cause))),
            result => json(Status.Ok, ChatProtocol.result(result))
          )
      }
    },
    Method.POST / "chat" / "stream" -> handler { (request: Request) =>
      withMessage(request) { message =>
        ZIO.succeed(
          Response(
            status = Status.Ok,
            headers = Headers(
              Header.ContentType(MediaType.text.`event-stream`),
              Header.CacheControl.NoCache
            ),
            body = Body.fromCharSequenceStreamChunked(events(message), Charsets.Utf8)
          )
        )
      }
    }
  )

  /** The run's events as server-sent events, with a heartbeat comment until the run's own events end. */
  private def events(message: String): ZStream[Any, Nothing, String] = {
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
      .catchAllCause(cause => ZStream.succeed(event("error", ChatProtocol.streamError(HttpErrors.of(llmError(cause))))))
    val keepAlive = ZStream.fromSchedule(Schedule.spaced(heartbeat)).as(ZioChatRoutes.KeepAlive)
    run.mergeHaltLeft(keepAlive)
  }

  private def withMessage(request: Request)(handle: String => UIO[Response]): UIO[Response] =
    request.body.asString.orElseSucceed("").flatMap { body =>
      ChatProtocol.message(body) match {
        case Right(message) => handle(message)
        case Left(invalid)  => ZIO.succeed(failed(HttpErrors.of(invalid)))
      }
    }

  private def failed(failure: HttpFailure): Response = {
    val response = json(Status.fromInt(failure.status), failure.body)
    failure.retryAfterSeconds.fold(response)(s => response.addHeader(Header.RetryAfter.ByDuration(s.seconds)))
  }

  private def json(status: Status, body: ujson.Value): Response =
    Response(
      status = status,
      headers = Headers(Header.ContentType(MediaType.application.json)),
      body = Body.fromString(body.render())
    )

  /** One server-sent event; the data is compact JSON, so it is one `data:` line. */
  private def event(name: String, data: ujson.Value): String =
    s"event: $name\ndata: ${data.render()}\n\n"

  /**
   * The `LLMError` a run failed with: `AgentZ` fails with it; a defect (a throwable, as no llm4s effect raises) is
   * unexpected.
   */
  private def llmError(cause: Cause[LLMError]): LLMError =
    cause.failureOption.getOrElse {
      val error = cause.squashWith(e => new IllegalStateException(e.message))
      UnknownError(Option(error.getMessage).getOrElse(error.getClass.getName), error)
    }
}

object ZioChatRoutes {

  /** How often an idle stream sends a comment. */
  val DefaultHeartbeat: Duration = 15.seconds

  /** The heartbeat: an SSE comment, which an `EventSource` ignores. */
  val KeepAlive: String = ": keep-alive\n\n"
}
