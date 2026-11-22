package org.llm4s.testkit

import com.sun.net.httpserver.HttpExchange

/**
 * One way a model's reply can end, which a chat client must report the same way on `complete` and on a stream (RFC
 * 0009, Part 5). A provider module's spec answers each case with its provider's own bytes, in
 * `ProviderModuleChecks.assertModelCallContract`, or declares it [[Applicability.NotApplicable]] with a reason, so a
 * gap shows in the spec instead of being missed.
 *
 * A case added later fails every spec that does not declare it, until it does.
 */
enum ContractCase:

  /** The model ended its turn with text: `Right`, `FinishReason.Stop`, the text, no tool call. */
  case CleanText

  /** The output limit cut the text short: `Right`, `FinishReason.Length`, the partial text. */
  case TextCutByLimit

  /** The model asked for a tool call: `Right`, `FinishReason.ToolCalls`, each call's arguments a JSON object. */
  case ToolCall

  /**
   * The model called a tool that takes no parameters, its arguments absent or blank (or `{}`), and the reply ended
   * cleanly: `Right`, `FinishReason.ToolCalls`, each call's arguments `{}` (RFC 0009, Part 3, rule 2).
   */
  case NoArgumentToolCall

  /**
   * The output limit cut the reply while it made a tool call: a `Left`, the malformed-tool-call
   * [[org.llm4s.error.ProcessingError ProcessingError]]. No call is run on intent the model did not finish stating,
   * even one whose arguments happen to parse (RFC 0009, Part 3, rule 3).
   */
  case ToolCallCutByLimit

  /**
   * A tool call's arguments are not a JSON object (invalid or cut JSON, or JSON of another type), in a reply that
   * otherwise ended cleanly: a `Left`, the malformed-tool-call [[org.llm4s.error.ProcessingError ProcessingError]],
   * never `{}`, never dropped (RFC 0009, Part 3, rule 1).
   */
  case MalformedArguments

  /** The provider's content filter stopped the reply: `Left(ContentFilteredError)`, where the provider has a filter. */
  case ContentFiltered

  /**
   * Stream only: the stream ended without the provider's end marker, after some text: a `Left` that a retry may fix,
   * a `NetworkError` or a `ServiceError` with status 502. The reply answers `streamComplete` only; build it with
   * [[RecordedReply.streamOnly]].
   */
  case StreamEndsEarly

  /**
   * Stream only: the provider reported an error in the stream, after the 200 status and some text: a `Left` whose
   * message carries [[ContractCase.ProviderErrorMessage]], which the reply's error must state. The reply answers
   * `streamComplete` only; build it with [[RecordedReply.streamOnly]].
   */
  case ErrorEvent

  /** Whether only `streamComplete` is run for this case: a non-streaming reply has no end marker or error event. */
  def streamOnly: Boolean = this match
    case StreamEndsEarly | ErrorEvent => true
    case _                            => false

object ContractCase:

  /** The message an [[ContractCase.ErrorEvent]] reply's error states, which the client's `Left` must carry. */
  val ProviderErrorMessage: String = "The model is overloaded (llm4s contract fixture)"

/**
 * How a provider answers one [[ContractCase]]: its reply to `complete` and its reply to `streamComplete`, each a
 * handler for a [[LocalProviderTestServer]] that writes the bytes the provider sends, captured from the real service
 * where possible.
 *
 * @param complete answers a non-streaming request, e.g. `LocalProviderTestServer.sendJsonResponse(_, 200, body)`
 * @param stream   answers a streaming request, e.g. `LocalProviderTestServer.sendSseResponse(_, body)`
 */
final case class RecordedReply(complete: HttpExchange => Unit, stream: HttpExchange => Unit)

object RecordedReply:

  /**
   * A reply for a stream-only case ([[ContractCase.streamOnly]]), which the check never sends `complete`: its
   * `complete` handler answers `500`.
   */
  def streamOnly(stream: HttpExchange => Unit): RecordedReply =
    RecordedReply(LocalProviderTestServer.sendJsonResponse(_, 500, notSent), stream)

  private val notSent = """{"error":{"message":"a stream-only contract case sends no complete request"}}"""

/** Whether a [[ContractCase]] applies to a client, and if it does, the provider's reply for it. */
enum Applicability:

  /** The case applies: the server answers with `reply`. */
  case Applies(reply: RecordedReply)

  /** The provider cannot produce the case, for `reason`, which the spec states so the gap is visible. */
  case NotApplicable(reason: String)
