package org.llm4s.portable.demo

import org.llm4s.error.{ LLMError, NetworkError }
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.portable.OpenAIChatProtocol
import org.llm4s.types.Result
import org.scalajs.dom

import scala.concurrent.{ ExecutionContext, Future }
import scala.scalajs.js
import scala.scalajs.js.typedarray.Uint8Array
import scala.util.{ Failure, Success }

/**
 * A streamed chat completion read from the browser's fetch body as it arrives, so each `StreamedChunk` reaches the
 * page while the model is still writing. sttp4's `FetchBackend` reads the body whole; this reads it incrementally
 * and feeds the same portable [[OpenAIChatProtocol.StreamDecoder]] the JVM and Native client use.
 *
 * No API key: point `baseUrl` at a local Ollama or at the application's own backend, which holds the key.
 */
object FetchStreaming {

  def streamComplete(baseUrl: String, model: String, conversation: Conversation, options: CompletionOptions)(
    onChunk: StreamedChunk => Unit
  )(using ExecutionContext): Future[Result[Completion]] =
    OpenAIChatProtocol.requestBody(model, conversation, options, stream = true) match {
      case Left(error) => Future.successful(Left(error))
      case Right(body) =>
        val init = new dom.RequestInit {}
        init.method = dom.HttpMethod.POST
        init.headers = js.Dictionary("Content-Type" -> "application/json")
        init.body = ujson.write(body)
        val url = baseUrl.stripSuffix("/") + "/chat/completions"
        dom
          .fetch(url, init)
          .toFuture
          .flatMap { response =>
            if (!response.ok)
              response
                .text()
                .toFuture
                .map(text => Left(OpenAIChatProtocol.decodeError(response.status, text, "ollama")))
            else {
              val decoder = new OpenAIChatProtocol.StreamDecoder
              val text    = js.Dynamic.newInstance(js.Dynamic.global.TextDecoder)()
              val reader  = response.body.getReader()
              def pump(): Future[Result[Completion]] =
                reader.read().toFuture.flatMap { chunk =>
                  if (chunk.done) {
                    decoder.feed(text.decode().asInstanceOf[String] + "\n\n").foreach(onChunk)
                    Future.successful(decoder.completion)
                  } else {
                    val piece = text.decode(chunk.value.asInstanceOf[Uint8Array], js.Dynamic.literal(stream = true))
                    decoder.feed(piece.asInstanceOf[String]).foreach(onChunk)
                    pump()
                  }
                }
              pump()
            }
          }
          .transform {
            case Success(result) => Success(result)
            case Failure(t)      => Success(Left(NetworkError(String.valueOf(t.getMessage), Some(t), url): LLMError))
          }
    }
}
