package org.llm4s.samples.chat.tui

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions, Conversation }
import termflow.tui.TuiPrelude.*
import termflow.tui.TermFlowError

import scala.concurrent.{ ExecutionContext, Future }

/**
 * Bridge between llm4s `streamComplete` (synchronous, callback-driven)
 * and TermFlow's `Cmd.asyncResult` (Future[Result[A]]).
 *
 * The token pump owns the chunks the LLM emits; this object only spawns
 * the background streamComplete call and maps llm4s errors into
 * `TermFlowError` so the runtime can surface them via the standard banner.
 */
object ChatTuiStreaming:

  /** Map an llm4s error into a TermFlow recoverable error. */
  def toTermFlowError(err: LLMError): TermFlowError =
    TermFlowError.Unexpected(err.formatted, None)

  /**
   * Kick off `streamComplete` on a background thread. Tokens land in
   * `pump.offer`; the returned `AsyncResult[Completion]` resolves with
   * the final completion (whose `toolCalls` may be non-empty).
   *
   * `streamComplete` runs synchronously inside the future; it doesn't
   * return until the provider emits a `finishReason`. Callers wire this
   * through `Cmd.asyncResult` so the Right/Left edges become the
   * `StreamComplete` / `StreamError` `Msg`s.
   */
  def start(
    client: LLMClient,
    conversation: Conversation,
    options: CompletionOptions,
    pump: TokenPump
  )(using ec: ExecutionContext): AsyncResult[Completion] =
    Future {
      client.streamComplete(conversation, options, onChunk = chunk => pump.offer(chunk))
    }.map {
      case Right(completion) => Right(completion)
      case Left(err)         => Left(toTermFlowError(err))
    }
