package org.llm4s.llmconnect.provider

import org.llm4s.llmconnect.model.{ AssistantMessage, StreamedChunk, ThinkingBlock, ToolCall }

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

/**
 * How the Gemini API and Vertex AI clients keep and replay a model turn's thought signatures.
 *
 * A thinking Gemini model attaches an opaque, base64 `thoughtSignature` to a part of its turn - to the
 * `functionCall` part when it calls a function, sometimes to the last text part otherwise - and expects
 * it back, on the same part, when the turn is sent again. Google documents the rules in the Vertex AI
 * guide "Thought signatures" (https://cloud.google.com/vertex-ai/generative-ai/docs/thought-signatures)
 * and the field's meaning in its SDKs (`Part.thoughtSignature`, "An opaque signature for the thought so
 * it can be reused in subsequent requests", in googleapis/js-genai `src/types.ts` and googleapis/python-genai
 * `google/genai/types.py`); the rules this class follows are:
 *
 *  - '''Function calls.''' A populated `functionCall.id` must be echoed by the matching
 *    `functionResponse`, so it becomes the tool call's id. If a response holds `functionCall` parts, a signature is required for correct
 *    processing, and in a response with parallel calls only the first `functionCall` part carries one.
 *    Gemini 3 models answer HTTP 400 when a required signature is not returned. The part must be sent back
 *    ''exactly as returned'', with its signature.
 *  - '''Other parts.''' A response without function calls may carry a signature on its last part, for
 *    example its last text part; sending it back is recommended and omitting it is not an error. Streaming
 *    can deliver it on a part with empty text.
 *  - '''Never merge''' a part that has a signature with one that has none, and never combine two signed parts.
 *
 * A signature is therefore kept as a sealed [[ThinkingBlock.Opaque]] block of the client's own provider id
 * (`gemini` or `vertexai`: the two are separate signing authorities here, because nothing in Google's
 * documentation says a signature from one validates on the other), beside the message content and tool
 * calls it was produced with, and is bound and replayed through [[ThinkingReplay]] like every other sealed
 * thinking: it is sent back only to the provider and model that produced it and only while the conversation
 * before it is unchanged. The block's `data` is a small JSON object that says which part the signature
 * belongs to:
 *
 *  - `{"call": "<tool call id>", "fc": <functionCall as returned>, "sig": "<signature>"}` for a
 *    `functionCall` part. The tool call's id is the `functionCall.id` Gemini populated, or one this client
 *    generated when it did not; `fc` is the part's payload exactly as returned (optional `id`, `args`
 *    present or absent), replayed verbatim while it still spells the tool call, so the signed part goes
 *    back exactly as received.
 *  - `{"at": <n>, "text": "<part text>", "sig": "<signature>"}` for a text part: `n` is the number of
 *    characters of message content that precede the part, and `text` is the part's own text (empty for a
 *    signature that arrived on an empty part). On replay the content is split at those offsets so a signed
 *    part is never merged with an unsigned one; if the content no longer fits them, the text signatures are
 *    left out, which Google says is not an error.
 *
 * Not kept: a signature on a part that is neither text nor a function call (an image part), and one on a
 * thought-summary part (`"thought": true`). Neither is requested or sent by this client. The order of parts
 * within a turn is rebuilt as text first, then function calls, as this client has always sent it.
 */
private[provider] object GeminiThoughtSignatures {

  /** The request and response field that carries a part's signature. */
  val Field = "thoughtSignature"

  /** The parts of one response candidate (or one stream chunk), split into what the clients keep. */
  final case class Parsed(
    text: String,
    thought: String,
    calls: Seq[ToolCall],
    signatures: Seq[ThinkingBlock]
  )

  /**
   * Splits `parts` into the answer text, the thought-summary text, the function calls and the signature
   * blocks, in part order.
   *
   * @param provider   the client's provider id, the `provider` of every signature block it makes
   * @param textOffset the number of message-content characters before the first of `parts`; 0 for a whole
   *                   response, the text streamed so far for a stream chunk
   * @param newId      makes the id of a function call that arrives without `functionCall.id`
   */
  def parse(provider: String, parts: Seq[ujson.Value], textOffset: Int, newId: () => String): Parsed = {
    val text       = new StringBuilder
    val thought    = new StringBuilder
    val calls      = ArrayBuffer.empty[ToolCall]
    val signatures = ArrayBuffer.empty[ThinkingBlock]

    parts.foreach { part =>
      val signature = signatureOf(part)
      val obj       = part.objOpt
      if (obj.exists(_.contains("functionCall"))) {
        val fc = part("functionCall")
        // Gemini may populate the optional functionCall.id, and a populated id must be echoed by the
        // matching functionResponse, so it becomes the tool call's own id; a UUID only when absent.
        val id   = fc.objOpt.flatMap(_.get("id")).flatMap(_.strOpt).filter(_.nonEmpty).getOrElse(newId())
        val call = ToolCall(id = id, name = fc("name").str, arguments = fc.obj.getOrElse("args", ujson.Obj()))
        calls += call
        signature.foreach(s => signatures += callBlock(provider, call.id, fc, s))
      } else if (isThought(part)) {
        obj.flatMap(_.get("text")).flatMap(_.strOpt).foreach(thought.append)
      } else {
        obj.flatMap(_.get("text")).flatMap(_.strOpt).foreach { t =>
          signature.foreach(s => signatures += textBlock(provider, textOffset + text.length, t, s))
          text.append(t)
        }
      }
    }
    Parsed(text.toString, thought.toString, calls.toSeq, signatures.toSeq)
  }

  /**
   * The parts of the model turn that `message` is: its text, split where a text signature sits, then its
   * function calls, each with the signature Gemini gave it, taken from the blocks `provider` produced.
   * The messages given here have already been through [[ThinkingReplay.replayable]], so any signature that
   * is still on a message is valid for this request.
   */
  def parts(provider: String, message: AssistantMessage): Seq[ujson.Value] = {
    val signed = Replay.from(provider, message.thinking)
    val text   = message.contentOpt.fold(Seq.empty[ujson.Value])(textParts(_, signed.text))
    val calls = message.toolCalls.map { tc =>
      def rebuilt = ujson.Obj("functionCall" -> ujson.Obj("name" -> tc.name, "args" -> tc.arguments))
      val part = signed.calls.get(tc.id) match {
        case Some(SignedCall(sig, Some(fc))) if matchesCall(fc, tc) =>
          // the signed part goes back exactly as returned: same id (or none), args present or absent
          val p = ujson.Obj("functionCall" -> fc)
          p(Field) = sig
          p
        case Some(SignedCall(_, Some(_))) =>
          rebuilt // the call was edited after signing: the signature no longer belongs to it
        case Some(SignedCall(sig, None)) =>
          val p = rebuilt // a block from before the payload was kept: rebuild, keep the signature
          p(Field) = sig
          p
        case None => rebuilt
      }
      part: ujson.Value
    }
    text ++ calls
  }

  /**
   * The stream chunks one parsed stream chunk is: one per function call (a chunk holds a single call), the
   * first carrying the text and the thought, the last carrying the finish reason; or a single chunk when
   * there is no call.
   */
  def chunks(parsed: Parsed, messageId: String, finishReason: Option[String]): Seq[StreamedChunk] = {
    val content = Option.when(parsed.text.nonEmpty)(parsed.text)
    val thought = Option.when(parsed.thought.nonEmpty)(parsed.thought)
    if (parsed.calls.isEmpty)
      Seq(
        StreamedChunk(
          id = messageId,
          content = content,
          toolCall = None,
          finishReason = finishReason,
          thinkingDelta = thought
        )
      )
    else
      parsed.calls.zipWithIndex.map { case (call, i) =>
        val first = i == 0
        val last  = i == parsed.calls.size - 1
        StreamedChunk(
          id = messageId,
          content = if (first) content else None,
          toolCall = Some(call),
          finishReason = if (last) finishReason else None,
          thinkingDelta = if (first) thought else None
        )
      }
  }

  // ---- the signature blocks

  // `fc` is the functionCall object exactly as Gemini returned it (its optional `id`, `args` present
  // or absent), so the signed part can be replayed exactly as received, as Google requires.
  private def callBlock(provider: String, callId: String, fc: ujson.Value, signature: String): ThinkingBlock =
    ThinkingBlock.Opaque(provider, ujson.Obj("call" -> callId, "fc" -> fc, "sig" -> signature).render())

  private def textBlock(provider: String, at: Int, text: String, signature: String): ThinkingBlock =
    ThinkingBlock.Opaque(provider, ujson.Obj("at" -> at, "text" -> text, "sig" -> signature).render())

  /**
   * Whether the stored functionCall still spells the tool call: same name, same id (when it had
   *  one: it became the call's id at parse), and the same arguments (`args` absent matches `{}`).
   */
  private def matchesCall(fc: ujson.Value, tc: ToolCall): Boolean =
    fc.objOpt.exists { o =>
      o.get("name").flatMap(_.strOpt).contains(tc.name) &&
      o.get("id").flatMap(_.strOpt).forall(_ == tc.id) &&
      o.get("args").fold(tc.arguments == (ujson.Obj(): ujson.Value))(_ == tc.arguments)
    }

  private def signatureOf(part: ujson.Value): Option[String] =
    part.objOpt.flatMap(_.get(Field)).flatMap(_.strOpt).filter(_.nonEmpty)

  private def isThought(part: ujson.Value): Boolean =
    part.objOpt.flatMap(_.get("thought")).exists(_.boolOpt.contains(true))

  /** A signed function call: the signature, and the functionCall payload exactly as returned. */
  final private case class SignedCall(sig: String, fc: Option[ujson.Value])

  /** The signatures of one provider found in a message's thinking, by what they belong to. */
  final private case class Replay(calls: Map[String, SignedCall], text: Seq[(Int, String, String)])

  private object Replay {
    def from(provider: String, thinking: Seq[ThinkingBlock]): Replay = {
      val parsed = thinking.flatMap {
        case ThinkingBlock.Opaque(p, data) if p == provider => Try(ujson.read(data)).toOption.flatMap(_.objOpt)
        case _                                              => None
      }
      val calls = parsed.flatMap { o =>
        for {
          id  <- o.get("call").flatMap(_.strOpt)
          sig <- o.get("sig").flatMap(_.strOpt)
        } yield id -> SignedCall(sig, o.get("fc"))
      }.toMap
      val text = parsed
        .flatMap { o =>
          for {
            at  <- o.get("at").flatMap(_.numOpt).map(_.toInt)
            t   <- o.get("text").flatMap(_.strOpt)
            sig <- o.get("sig").flatMap(_.strOpt)
          } yield (at, t, sig)
        }
        .sortBy(_._1)
      Replay(calls, text)
    }
  }

  /**
   * `content` as text parts: split at the signed parts' offsets, each signed part carrying its signature and
   * the rest unsigned. When the signed parts do not fit the content (they overlap, run past it, or no longer
   * spell what they did) the text is sent as one unsigned part, because a text signature is optional.
   */
  private def textParts(content: String, signed: Seq[(Int, String, String)]): Seq[ujson.Value] = {
    def plain(t: String): ujson.Value = ujson.Obj("text" -> t)

    val fits = signed.foldLeft(Option(0)) {
      case (Some(cursor), (at, text, _)) if at >= cursor && content.startsWith(text, at) => Some(at + text.length)
      case _                                                                             => None
    }
    if (signed.isEmpty || fits.isEmpty) Seq(plain(content))
    else {
      val out    = ArrayBuffer.empty[ujson.Value]
      var cursor = 0
      signed.foreach { case (at, text, signature) =>
        if (at > cursor) out += plain(content.substring(cursor, at))
        out += ujson.Obj("text" -> text, Field -> signature)
        cursor = at + text.length
      }
      if (cursor < content.length) out += plain(content.substring(cursor))
      out.toSeq
    }
  }
}
