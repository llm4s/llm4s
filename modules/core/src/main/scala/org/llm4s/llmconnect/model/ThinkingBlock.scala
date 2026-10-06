package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable
import upickle.default.{ readwriter, ReadWriter => RW }

/**
 * One block of the reasoning a model produced before its answer, as carried on
 * [[AssistantMessage.thinking]] so that it can be sent back in later requests.
 *
 * Most providers report thinking as plain text - Ollama's `thinking`, DeepSeek's and Z.ai's
 * `reasoning_content`, OpenRouter's `reasoning`, Mistral's thinking chunks - and a message then
 * holds a single [[ThinkingBlock.Text]] with no signature. Anthropic (directly or through
 * Bedrock) returns one or more blocks, each signed, and some redacted; it requires them back
 * unchanged, in order, when a thinking model's turn ends in tool calls. So the blocks are
 * kept as the provider returned them, and a client replays only what its provider accepts:
 * Anthropic and Bedrock send signed and redacted blocks and leave unsigned text out (they would
 * reject it), the other providers send the text.
 */
@Stable
enum ThinkingBlock:

  /**
   * Reasoning text.
   *
   * @param text      The model's reasoning. May be empty when the provider withholds the text but
   *                  still signs the block, as Anthropic does for omitted thinking.
   * @param signature The provider's signature over `text`, when it signs it (Anthropic, Bedrock).
   *                  Opaque: passed back unchanged and never interpreted.
   */
  case Text(text: String, signature: Option[String] = None)

  /**
   * Reasoning the provider encrypted rather than returned (Anthropic's `redacted_thinking`).
   *
   * @param data Opaque payload, passed back unchanged.
   */
  case Redacted(data: String)

object ThinkingBlock:

  /** The concatenated text of the [[Text]] blocks of `blocks`, or `None` when there is none. */
  def text(blocks: Seq[ThinkingBlock]): Option[String] = {
    val joined = blocks.collect { case Text(text, _) => text }.mkString
    Option.when(joined.nonEmpty)(joined)
  }

  /**
   * Whether `block` is sealed - signed text or redacted data - and so valid only beside the exact
   * message content and tool calls it came with (see [[AssistantMessage]]).
   */
  def isSealed(block: ThinkingBlock): Boolean = block match {
    case Text(_, signature) => signature.exists(_.nonEmpty)
    case Redacted(_)        => true
  }

  implicit val rw: RW[ThinkingBlock] = readwriter[ujson.Value].bimap[ThinkingBlock](
    {
      case Text(text, signature) =>
        val obj = ujson.Obj("type" -> "text", "text" -> text)
        signature.foreach(s => obj("signature") = s)
        obj
      case Redacted(data) => ujson.Obj("type" -> "redacted", "data" -> data)
    },
    json => {
      val obj = json.obj
      obj.get("type").flatMap(_.strOpt) match {
        case Some("redacted") => Redacted(obj("data").str)
        case _                => Text(obj("text").str, obj.get("signature").flatMap(_.strOpt))
      }
    }
  )
