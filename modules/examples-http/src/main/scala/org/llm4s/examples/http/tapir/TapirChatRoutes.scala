package org.llm4s.examples.http.tapir

import cats.effect.IO
import org.http4s.{ HttpRoutes, ServerSentEvent }
import org.llm4s.effect.cats.AgentIO
import org.llm4s.examples.http.{ ChatProtocol, HttpErrors }
import org.llm4s.examples.http.http4s.{ ChatEvents, ChatRoutes }
import sttp.apispec.openapi.circe.yaml.*
import sttp.capabilities.fs2.Fs2Streams
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.swagger.{ SwaggerUI, SwaggerUIOptions }

import scala.concurrent.duration.FiniteDuration

import ChatEndpoints.ErrorResponse

/**
 * [[ChatEndpoints]] with their logic over an [[org.llm4s.effect.cats.AgentIO AgentIO]], interpreted as http4s routes
 * by `tapir-http4s-server`, plus the OpenAPI documentation: `GET /docs` (Swagger UI) and `GET /docs/openapi.yaml`.
 *
 * The logic is the http4s app's: `/chat` runs `AgentIO.run` and maps a failure through
 * [[org.llm4s.examples.http.HttpErrors.of HttpErrors.of]] to a typed [[ChatEndpoints.ErrorResponse ErrorResponse]];
 * `/chat/stream` encodes the same [[org.llm4s.examples.http.http4s.ChatEvents ChatEvents]] as server-sent events,
 * so a client that disconnects cancels the run in the same way.
 */
final class TapirChatRoutes(agent: AgentIO[IO], heartbeat: FiniteDuration = ChatRoutes.DefaultHeartbeat) {

  /** `POST /chat` with its logic. */
  val chat: ServerEndpoint[Any, IO] =
    ChatEndpoints.chat.serverLogic[IO] { body =>
      ChatProtocol.message(body) match {
        case Left(invalid) => IO.pure(Left(ErrorResponse.of(HttpErrors.of(invalid))))
        case Right(message) =>
          agent.run(message).attempt.map {
            case Right(result) => Right(ChatProtocol.result(result))
            case Left(error)   => Left(ErrorResponse.of(HttpErrors.of(ChatEvents.llmError(error))))
          }
      }
    }

  /** `POST /chat/stream` with its logic. */
  val chatStream: ServerEndpoint[Fs2Streams[IO], IO] =
    ChatEndpoints.chatStream.serverLogicPure[IO] { body =>
      ChatProtocol
        .message(body)
        .left
        .map(invalid => ErrorResponse.of(HttpErrors.of(invalid)))
        .map(message => ChatEvents(agent, message, heartbeat).through(ServerSentEvent.encoder[IO]))
    }

  /** Swagger UI at `/docs`, over the OpenAPI document at `/docs/openapi.yaml`. */
  val docs: List[ServerEndpoint[Any, IO]] =
    SwaggerUI[IO](ChatEndpoints.openApi.toYaml, SwaggerUIOptions.default.yamlName(TapirChatRoutes.OpenApiFile))

  val routes: HttpRoutes[IO] =
    Http4sServerInterpreter[IO]().toRoutes(List[ServerEndpoint[Fs2Streams[IO], IO]](chat, chatStream) ++ docs)
}

object TapirChatRoutes {

  /** The OpenAPI document's name under `/docs`. */
  val OpenApiFile: String = "openapi.yaml"
}
