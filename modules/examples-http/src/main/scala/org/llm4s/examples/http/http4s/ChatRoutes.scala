package org.llm4s.examples.http.http4s

import cats.effect.IO
import org.http4s.{ HttpRoutes, MediaType, Request, Response, Status }
import org.http4s.dsl.io.*
import org.http4s.headers.{ `Cache-Control`, `Content-Type`, `Retry-After` }
import org.http4s.CacheDirective
import org.llm4s.effect.cats.AgentIO
import org.llm4s.examples.http.{ ChatProtocol, HttpErrors, HttpFailure }

import scala.concurrent.duration.*

/**
 * `POST /chat` and `POST /chat/stream` over an [[org.llm4s.effect.cats.AgentIO AgentIO]].
 *
 * `/chat` runs one turn and answers its result as JSON, or the mapped error status ([[HttpErrors.of]]).
 *
 * `/chat/stream` runs the turn as `AgentIO.stream` and sends it as server-sent events ([[ChatEvents]]; see
 * [[org.llm4s.examples.http.ChatProtocol ChatProtocol]]). The status is `200` once the request is valid, so an error
 * of the run arrives as an `error` event carrying the status it maps to. A comment is sent every `heartbeat`, which
 * keeps proxies from closing a quiet stream and makes a client that has gone away visible to the server: the next
 * write fails, the response stream is interrupted, and interrupting `AgentIO.stream` cancels the run, which
 * interrupts the provider call.
 */
final class ChatRoutes(agent: AgentIO[IO], heartbeat: FiniteDuration = ChatRoutes.DefaultHeartbeat) {

  val routes: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "chat" =>
      withMessage(request) { message =>
        agent.run(message).attempt.flatMap {
          case Right(result) => json(Status.Ok, ChatProtocol.result(result))
          case Left(error)   => failed(HttpErrors.of(ChatEvents.llmError(error)))
        }
      }

    case request @ POST -> Root / "chat" / "stream" =>
      withMessage(request) { message =>
        Ok(ChatEvents(agent, message, heartbeat)).map(_.putHeaders(`Cache-Control`(CacheDirective.`no-cache`())))
      }
  }

  private def withMessage(request: Request[IO])(handle: String => IO[Response[IO]]): IO[Response[IO]] =
    request.as[String].flatMap { body =>
      ChatProtocol.message(body) match {
        case Right(message) => handle(message)
        case Left(invalid)  => failed(HttpErrors.of(invalid))
      }
    }

  private def failed(failure: HttpFailure): IO[Response[IO]] =
    json(Status.fromInt(failure.status).getOrElse(Status.BadGateway), failure.body).map { response =>
      failure.retryAfterSeconds.fold(response)(s => response.putHeaders(`Retry-After`.unsafeFromLong(s)))
    }

  private def json(status: Status, body: ujson.Value): IO[Response[IO]] =
    IO.pure(
      Response[IO](status)
        .withEntity(body.render())
        .withContentType(`Content-Type`(MediaType.application.json))
    )
}

object ChatRoutes {

  /** How often an idle stream sends a comment. */
  val DefaultHeartbeat: FiniteDuration = 15.seconds
}
