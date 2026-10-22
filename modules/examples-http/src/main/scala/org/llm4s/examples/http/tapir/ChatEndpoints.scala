package org.llm4s.examples.http.tapir

import cats.effect.IO
import fs2.Stream
import org.llm4s.examples.http.HttpFailure
import sttp.apispec.openapi.OpenAPI
import sttp.capabilities.fs2.Fs2Streams
import sttp.model.{ HeaderNames, StatusCode }
import sttp.tapir.*
import sttp.tapir.docs.openapi.OpenAPIDocsInterpreter

import java.nio.charset.StandardCharsets
import scala.util.Try

import ChatSchemas.{ ChatRequest, ChatResponse, ErrorBody }

/**
 * The reference apps' API as Tapir endpoint definitions: `POST /chat` and `POST /chat/stream`, with the same JSON
 * ([[org.llm4s.examples.http.ChatProtocol ChatProtocol]]) and the same error statuses
 * ([[org.llm4s.examples.http.HttpErrors HttpErrors]]) as the http4s and ZIO HTTP apps.
 *
 * The definitions say nothing about the server: [[TapirChatRoutes]] gives them their logic and interprets them as
 * http4s routes, and [[openApi]] interprets the same values as OpenAPI documentation, so the docs cannot drift from
 * what is served.
 *
 * The bodies are the shared protocol's JSON, carried as `ujson` values; [[ChatSchemas]] describes that JSON for the
 * documentation.
 */
object ChatEndpoints {

  /**
   * A typed error output: the status [[org.llm4s.examples.http.HttpErrors.of HttpErrors.of]] chose, the
   * `Retry-After` delay it carries (rate limits only) and the error body.
   */
  final case class ErrorResponse(status: StatusCode, retryAfterSeconds: Option[Long], body: ujson.Value)

  object ErrorResponse {

    /** The response for a mapped error. */
    def of(failure: HttpFailure): ErrorResponse =
      ErrorResponse(StatusCode(failure.status), failure.retryAfterSeconds, failure.body)
  }

  /** The statuses `HttpErrors.of` answers with, each a documented variant of the error output. */
  val ErrorStatuses: Seq[StatusCode] = Seq(
    StatusCode.BadRequest,
    StatusCode.TooManyRequests,
    StatusCode.InternalServerError,
    StatusCode.BadGateway,
    StatusCode.ServiceUnavailable,
    StatusCode.GatewayTimeout
  )

  /** `POST /chat`: the turn's result as JSON, or an error status. */
  val chat: PublicEndpoint[String, ErrorResponse, ujson.Value, Any] =
    endpoint.post
      .in("chat")
      .in(requestBody)
      .out(jsonBody(summon[Schema[ChatResponse]]).description("The turn's result."))
      .errorOut(oneOf[ErrorResponse](variant(StatusCode.BadRequest), ErrorStatuses.tail.map(variant)*))
      .summary("Run one agent turn and answer its result")

  /**
   * `POST /chat/stream`: the run as server-sent events - `delta`, `tool_call`, then `done` or `error` - with a
   * `keep-alive` comment while the run is quiet. Only an invalid request fails before the stream starts; an error
   * of the run is the stream's `error` event, with the status it maps to.
   */
  val chatStream: PublicEndpoint[String, ErrorResponse, Stream[IO, Byte], Fs2Streams[IO]] =
    endpoint.post
      .in("chat" / "stream")
      .in(requestBody)
      .out(header(HeaderNames.CacheControl, "no-cache"))
      .out(
        streamTextBody(Fs2Streams[IO])(CodecFormat.TextEventStream(), Some(StandardCharsets.UTF_8))
          .description(
            "Server-sent events: `delta` ({attempt, text}), `tool_call` ({id, tool}), then one `done` (the /chat " +
              "body) or one `error` (the error body with its `status`)."
          )
      )
      .errorOut(oneOf[ErrorResponse](variant(StatusCode.BadRequest)))
      .summary("Run one agent turn and stream its events")

  /** Both endpoints, as the documentation lists them. */
  val all: List[AnyEndpoint] = List(chat, chatStream)

  /** The OpenAPI documentation of [[all]]. */
  def openApi: OpenAPI = OpenAPIDocsInterpreter().toOpenAPI(all, "llm4s chat", "1.0")

  /**
   * `{"message": "..."}`, read as text: [[org.llm4s.examples.http.ChatProtocol.message ChatProtocol.message]] decides
   * what is valid, so a bad body is a `400` with the shared error body, as in the other apps, not Tapir's own.
   */
  private def requestBody: EndpointIO.Body[String, String] =
    stringJsonBody.schema(summon[Schema[ChatRequest]].as[String]).description("""{"message": "..."}""")

  /** A JSON body of `ujson` values, documented as `schema`. */
  private[tapir] def jsonBody[T](schema: Schema[T]): EndpointIO.Body[String, ujson.Value] =
    stringBodyUtf8AnyFormat(
      Codec.string
        .format(CodecFormat.Json())
        .mapDecode(text => Try(ujson.read(text)).fold(DecodeResult.Error(text, _), DecodeResult.Value(_)))(_.render())
        .schema(schema.as[ujson.Value])
    )

  /** The error output for `status`; a rate limit's carries `Retry-After`. */
  private def variant(status: StatusCode): EndpointOutput.OneOfVariant[ErrorResponse] = {
    val body = jsonBody(summon[Schema[ErrorBody]]).description(describe(status))
    val output: EndpointOutput[ErrorResponse] =
      if (status == StatusCode.TooManyRequests)
        header[Option[Long]](HeaderNames.RetryAfter)
          .description("Seconds to wait before trying again.")
          .and(body)
          .map((retry, error) => ErrorResponse(status, retry, error))(e => (e.retryAfterSeconds, e.body))
      else body.map(ErrorResponse(status, None, _))(_.body)
    oneOfVariantValueMatcher(status, output) {
      case e: ErrorResponse if e.status == status => true
    }
  }

  private def describe(status: StatusCode): String = status.code match {
    case 400 => "The body is not {\"message\": \"<non-blank>\"} (ValidationError, InvalidInputError)."
    case 429 => "The provider's rate limit (RateLimitError)."
    case 500 => "The server is misconfigured (ConfigurationError)."
    case 502 => "The provider refused the server's credential, or failed in a way retrying will not fix."
    case 503 => "A recoverable provider failure (network, provider 5xx): try again."
    case _   => "The provider timed out (TimeoutError)."
  }
}
