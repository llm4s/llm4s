package org.llm4s.examples.http.playframework

import org.llm4s.examples.http.{ ChatProtocol, HttpErrors, HttpFailure }
import org.llm4s.pekko.AgentPekko
import play.api.Configuration
import play.api.http.{ ContentTypes, HeaderNames }
import play.api.mvc.{ AbstractController, Action, ControllerComponents, Result }

import javax.inject.{ Inject, Singleton }
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.FiniteDuration

/**
 * `POST /chat` and `POST /chat/stream` over an [[org.llm4s.pekko.AgentPekko AgentPekko]].
 *
 * `/chat` runs one turn with `AgentPekko.run`, which waits for the turn on [[ChatExecutionContext]] - never on Play's
 * default dispatcher - and answers its result as JSON, or the mapped error status ([[HttpErrors.of]]).
 *
 * `/chat/stream` answers `Ok.chunked` of [[PlayChatEvents]], `AgentPekko.stream` as server-sent events. The status is
 * `200` once the request is valid, so an error of the run arrives as an `error` event carrying the status it maps to.
 * When the client goes away, Play's server cancels the response source, which cancels the run and interrupts its
 * provider call; the `heartbeat` comment keeps proxies from closing a quiet stream.
 */
@Singleton
final class ChatController @Inject() (
  cc: ControllerComponents,
  agent: AgentPekko,
  blocking: ChatExecutionContext,
  configuration: Configuration
) extends AbstractController(cc) {

  private given ExecutionContext = cc.executionContext

  private val heartbeat = configuration.get[FiniteDuration](s"${ChatModule.ConfigPath}.heartbeat")

  /** `POST /chat`: the turn's result, or the status its error maps to. */
  def chat: Action[String] = Action.async(parse.tolerantText) { request =>
    ChatProtocol.message(request.body) match {
      case Left(invalid) => Future.successful(failed(HttpErrors.of(invalid)))
      case Right(message) =>
        agent
          .run(message)(using blocking)
          .map(result => Ok(ChatProtocol.result(result).render()).as(ContentTypes.JSON))
          .recover { case error => failed(HttpErrors.of(PlayChatEvents.llmError(error))) }
    }
  }

  /** `POST /chat/stream`: the turn as server-sent events, or `400` for a request without a message. */
  def chatStream: Action[String] = Action(parse.tolerantText) { request =>
    ChatProtocol.message(request.body) match {
      case Left(invalid) => failed(HttpErrors.of(invalid))
      case Right(message) =>
        Ok.chunked(PlayChatEvents(agent, message, heartbeat))
          .as(ContentTypes.EVENT_STREAM)
          .withHeaders(HeaderNames.CACHE_CONTROL -> "no-cache")
    }
  }

  private def failed(failure: HttpFailure): Result = {
    val response = Status(failure.status)(failure.body.render()).as(ContentTypes.JSON)
    failure.retryAfterSeconds.fold(response)(s => response.withHeaders(HeaderNames.RETRY_AFTER -> s.toString))
  }
}
