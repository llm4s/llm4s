package org.llm4s.portable

import org.llm4s.error.{ LLMError, NetworkError }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.types.Result
import sttp.client4._
import sttp.model.Uri

import scala.util.control.NonFatal

/**
 * An OpenAI-compatible chat client over sttp4 that compiles for the JVM, Scala.js and Scala Native. The backend picks
 * the platform and the effect: `FetchBackend()` (Future) in a browser or Node, `CurlBackend()` (synchronous) on
 * Scala Native, any sttp backend on the JVM.
 *
 * `streamComplete` asks for a streamed response and delivers core's `StreamedChunk`s, but sttp4 reads the body
 * whole before it returns it unless a streaming capability (fs2, ZIO, Pekko) is plugged in, and none of those is
 * portable to all three platforms; so chunks arrive together once the reply has ended. The Laminar demo reads
 * the browser's fetch body incrementally with the same [[OpenAIChatProtocol.StreamDecoder]] instead.
 *
 * @param baseUrl for example `http://localhost:11434/v1` (Ollama) or the application's own backend proxy.
 * @param apiKey  sent as a bearer token when present. Never give one to code that runs in a browser.
 */
final class PortableChatClient[F[_]](
  backend: Backend[F],
  baseUrl: String,
  model: String,
  apiKey: Option[String] = None,
  provider: String = "openai-compatible"
) {
  private val monad = backend.monad

  def complete(conversation: Conversation, options: CompletionOptions = CompletionOptions()): F[Result[Completion]] =
    send(conversation, options, stream = false)(OpenAIChatProtocol.decodeCompletion)

  def streamComplete(
    conversation: Conversation,
    options: CompletionOptions = CompletionOptions()
  )(onChunk: StreamedChunk => Unit): F[Result[Completion]] =
    send(conversation, options, stream = true) { body =>
      val decoder = new OpenAIChatProtocol.StreamDecoder
      decoder.feed(body + "\n\n").foreach(onChunk)
      decoder.completion
    }

  private def send(conversation: Conversation, options: CompletionOptions, stream: Boolean)(
    decode: String => Result[Completion]
  ): F[Result[Completion]] =
    OpenAIChatProtocol
      .requestBody(model, conversation, options, stream)
      .flatMap(body => endpoint.map(_ -> body)) match {
      case Left(error) => monad.unit(Left(error))
      case Right((uri, body)) =>
        val request = basicRequest
          .post(uri)
          .contentType("application/json")
          .headers(apiKey.map(k => Map("Authorization" -> s"Bearer $k")).getOrElse(Map.empty))
          .body(ujson.write(body))
          .response(asStringAlways)
        monad.handleError(monad.map(request.send(backend)) { response =>
          if (response.code.isSuccess) decode(response.body)
          else Left(OpenAIChatProtocol.decodeError(response.code.code, response.body, provider))
        }) { case NonFatal(t) => monad.unit(Left(networkError(t))) }
    }

  private def endpoint: Result[Uri] =
    Uri
      .parse(baseUrl.stripSuffix("/") + "/chat/completions")
      .left
      .map(reason => NetworkError(s"invalid base URL: $reason", None, baseUrl): LLMError)

  /** sttp wraps the transport's exception; the innermost cause says what went wrong ("connection refused"). */
  private def networkError(t: Throwable): LLMError = {
    val root = Iterator.iterate(t)(_.getCause).takeWhile(_ != null).toSeq.last
    NetworkError(Option(root.getMessage).getOrElse(root.getClass.getName), Some(t), baseUrl)
  }
}
