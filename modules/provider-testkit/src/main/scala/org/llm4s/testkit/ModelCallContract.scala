package org.llm4s.testkit

import com.sun.net.httpserver.HttpExchange

/**
 * One way a model's reply can end, which a chat client must report the same way on `complete` and on a stream (RFC
 * 0009, Part 5). A provider module's spec answers each case with its provider's own bytes, in
 * `ProviderModuleChecks.assertModelCallContract`, or declares it [[Applicability.NotApplicable]] with a reason, so a
 * gap shows in the spec instead of being missed.
 *
 * Later RFC 0009 PRs add the tool-argument cases (`NoArgumentToolCall`, `ToolCallCutByLimit`, `MalformedArguments`)
 * and the stream-ending cases (`StreamEndsEarly`, `ErrorEvent`); a spec that does not declare a new case fails until
 * it does.
 */
enum ContractCase:

  /** The model ended its turn with text: `Right`, `FinishReason.Stop`, the text, no tool call. */
  case CleanText

  /** The output limit cut the text short: `Right`, `FinishReason.Length`, the partial text. */
  case TextCutByLimit

  /** The model asked for a tool call: `Right`, `FinishReason.ToolCalls`, each call's arguments a JSON object. */
  case ToolCall

  /** The provider's content filter stopped the reply: `Left(ContentFilteredError)`, where the provider has a filter. */
  case ContentFiltered

/**
 * How a provider answers one [[ContractCase]]: its reply to `complete` and its reply to `streamComplete`, each a
 * handler for a [[LocalProviderTestServer]] that writes the bytes the provider sends, captured from the real service
 * where possible.
 *
 * @param complete answers a non-streaming request, e.g. `LocalProviderTestServer.sendJsonResponse(_, 200, body)`
 * @param stream   answers a streaming request, e.g. `LocalProviderTestServer.sendSseResponse(_, body)`
 */
final case class RecordedReply(complete: HttpExchange => Unit, stream: HttpExchange => Unit)

/** Whether a [[ContractCase]] applies to a client, and if it does, the provider's reply for it. */
enum Applicability:

  /** The case applies: the server answers with `reply`. */
  case Applies(reply: RecordedReply)

  /** The provider cannot produce the case, for `reason`, which the spec states so the gap is visible. */
  case NotApplicable(reason: String)
