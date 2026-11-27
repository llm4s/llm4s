package org.llm4s.llmconnect.provider

import org.llm4s.error.LLMError
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, StreamedChunk, SystemMessage, ToolMessage, UserMessage }
import org.llm4s.syntax.toLLMError
import org.llm4s.types.ProviderModelTypes.ProviderId
import org.llm4s.types.Result

import java.util.UUID
import scala.collection.mutable
import scala.util.Try

/**
 * The request and stream encoding [[GeminiClient]] and [[VertexAIClient]] share: the two speak the same
 * `generateContent` format, so a fix to one applies to both (#3124).
 *
 *  - Every [[SystemMessage]] is sent, in order, as its own `systemInstruction.parts` entry.
 *  - A run of [[ToolMessage]]s is one `user` content: one `functionResponse` per result paired with a call of the
 *    model turn straight before it ([[ToolResultPairing]]), named for that call, then each unpaired result as text.
 *  - A stream chunk that cannot be read is an error, as the same reply is on `complete`.
 */
private[provider] object GeminiWire {

  /** A request's `systemInstruction`, when it has one, and its `contents`. */
  final case class Encoded(systemInstruction: Option[ujson.Obj], contents: Vector[ujson.Value])

  /**
   * `messages` as Gemini's `systemInstruction` and `contents`.
   *
   * @param provider the client's provider id: the thought signatures it produced go back
   * @param messages the conversation, already through `ThinkingReplay`
   * @param fileUri  resolves a media reference to the client's `fileData.fileUri`
   */
  def encode(provider: ProviderId, messages: Seq[Message], fileUri: GeminiParts.FileUri): Encoded = {
    val indexed  = messages.toIndexedSeq
    val pairing  = ToolResultPairing.of(indexed)
    val contents = mutable.ArrayBuffer.empty[ujson.Value]
    val systems  = mutable.ArrayBuffer.empty[String]

    // the paired calls of the latest model turn, by id: the calls its results answer
    var turnCalls         = Map.empty[String, String]
    val turnIdsSentWithId = mutable.Set.empty[String]
    // the run of tool results being collected into one user content: native results first, then text
    val pendingResults = mutable.ArrayBuffer.empty[ujson.Value]
    val pendingTexts   = mutable.ArrayBuffer.empty[ujson.Value]

    def flushResults(): Unit =
      if (pendingResults.nonEmpty || pendingTexts.nonEmpty) {
        contents += ujson.Obj("role" -> "user", "parts" -> ujson.Arr.from(pendingResults ++ pendingTexts))
        pendingResults.clear()
        pendingTexts.clear()
      }

    indexed.zipWithIndex.foreach {
      case (SystemMessage(content), _) =>
        // lifted out of the contents, so it does not break a run of tool results
        systems += content

      case (um: UserMessage, _) =>
        flushResults()
        // checked before the body is built: GeminiParts covers every part
        contents += ujson.Obj(
          "role"  -> "user",
          "parts" -> ujson.Arr.from(PartEncoding.withoutBlankText(um.parts).map(GeminiParts.user(fileUri)))
        )

      case (am: AssistantMessage, index) =>
        flushResults()
        turnCalls =
          am.toolCalls.filter(tc => pairing.callPaired(index, tc.id)).map(tc => tc.id.asString -> tc.name).toMap
        turnIdsSentWithId.clear()
        // text, then function calls, each part carrying the thought signature Gemini gave it (if it is still
        // valid); every call goes back, so no signature Gemini wants preserved is lost
        val parts = GeminiThoughtSignatures.parts(provider, am)
        // a functionCall part sent with Gemini's own id needs that id echoed on its functionResponse; an empty
        // id is "no id", as GeminiThoughtSignatures.parse and matchesCall read it
        parts.foreach { p =>
          p.objOpt
            .flatMap(_.get("functionCall"))
            .flatMap(_.objOpt)
            .flatMap(_.get("id"))
            .flatMap(_.strOpt)
            .filter(_.nonEmpty)
            .foreach(turnIdsSentWithId += _)
        }
        if (parts.nonEmpty && (am.toolCalls.nonEmpty || am.text.isDefined))
          contents += ujson.Obj("role" -> "model", "parts" -> ujson.Arr.from(parts))

      case (tm: ToolMessage, index) if pairing.resultPaired(index) =>
        val id       = tm.toolCallId.asString
        val response = ujson.Obj("name" -> turnCalls(id), "response" -> ujson.Obj("result" -> tm.content))
        // a populated functionCall.id must come back on the matching functionResponse
        if (turnIdsSentWithId.contains(id)) response("id") = id
        pendingResults += ujson.Obj("functionResponse" -> response)

      case (tm: ToolMessage, _) =>
        // no call of the turn before it to answer (pruned, or not straight before it): sent as text, as the
        // other providers send it, never under a made-up function name
        pendingTexts += ujson.Obj("text" -> s"[Tool result for ${tm.toolCallId}]: ${tm.content}")
    }
    flushResults()

    val systemInstruction = Option.when(systems.nonEmpty)(
      ujson.Obj("parts" -> ujson.Arr.from(systems.map(s => ujson.Obj("text" -> s))))
    )
    Encoded(systemInstruction, contents.toVector)
  }

  /**
   * The error for the first `functionCall` part of `parts` without a `name` string, which no call can be made of.
   * Checked before the parts are read, on `complete` and on each stream chunk alike.
   */
  def malformedCall(parts: Seq[ujson.Value]): Option[LLMError] =
    parts.iterator
      .flatMap(_.objOpt.flatMap(_.get("functionCall")))
      .collectFirst {
        case fc if fc.objOpt.flatMap(_.get("name")).flatMap(_.strOpt).forall(_.trim.isEmpty) =>
          ToolCallRules.malformed("a functionCall has no name")
      }

  /** [[malformedCall]] for the first candidate of a whole `generateContent` reply. */
  def malformedReply(json: ujson.Value): Option[LLMError] =
    json.objOpt
      .flatMap(_.get("candidates"))
      .flatMap(_.arrOpt)
      .flatMap(_.headOption)
      .flatMap(candidate => malformedCall(GeminiThoughtSignatures.partsOf(candidate)))

  /**
   * One stream chunk: the parts it held and the [[StreamedChunk]]s they make, one per function call (a chunk holds a
   * single call), or one when there is none; `None` for a chunk with no candidate (one carrying only usage). A
   * chunk that cannot be read is a `Left`, so the stream fails rather than lose its text or call.
   *
   * @param textOffset  the answer-text characters streamed before this chunk, so a text signature records its place
   * @param callsBefore whether a function call streamed in an earlier chunk: a `STOP` after one is `ToolCalls`
   */
  def streamChunk(
    provider: ProviderId,
    json: ujson.Value,
    messageId: String,
    textOffset: Int,
    callsBefore: Boolean
  ): Result[Option[(GeminiThoughtSignatures.Parsed, Seq[StreamedChunk])]] =
    json.objOpt.flatMap(_.get("candidates")).flatMap(_.arrOpt).flatMap(_.headOption) match {
      case None => Right(None)
      case Some(candidate) =>
        val parts = GeminiThoughtSignatures.partsOf(candidate) // none: the chunk still ends the stream
        malformedCall(parts).toLeft(()).flatMap { _ =>
          Try {
            val parsed = GeminiThoughtSignatures.parse(provider, parts, textOffset, () => UUID.randomUUID().toString)
            // the finish reason, ToolCalls for a STOP after a function call in this chunk or an earlier one
            val finishReason = candidate.objOpt
              .flatMap(_.get("finishReason"))
              .flatMap(_.strOpt)
              .map(raw => GeminiBlocked.mapped(Some(raw), callsBefore || parsed.calls.nonEmpty))
            Some((parsed, GeminiThoughtSignatures.chunks(parsed, messageId, finishReason)))
          }.toEither.left.map(_.toLLMError)
        }
    }
}
