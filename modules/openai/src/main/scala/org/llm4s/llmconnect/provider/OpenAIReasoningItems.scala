package org.llm4s.llmconnect.provider

import com.openai.core.ObjectMappers
import com.openai.models.responses.{ ResponseOutputItem, ResponseReasoningItem }
import org.llm4s.llmconnect.model.ThinkingBlock
import org.llm4s.types.ProviderModelTypes.ProviderId

import scala.jdk.CollectionConverters._
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * A Responses reply's `reasoning` items as thinking blocks, and back.
 *
 * Each item with `encrypted_content` is kept as a `ThinkingBlock.Opaque` of provider `openai` whose data is the
 * envelope `{"item": <the item as returned>, "before": "<call_id>"}`; `before` is the `call_id` of the
 * `function_call` that follows it in the output, looking past any reasoning items between, and is absent when the
 * next other item is anything else (a message, say). A reasoning item with no later message or call (a reply cut
 * off in its reasoning, or reasoning after the turn's text) is not kept for replay, because OpenAI rejects a
 * reasoning item with nothing after it; its summary is. An item's summary text is a `ThinkingBlock.Text` before its opaque
 * block. Nothing here throws: what cannot be read is left out.
 */
private[provider] object OpenAIReasoningItems:

  val Provider: ProviderId = ProviderId("openai")

  /** The thinking blocks for a reply's output items, in output order. */
  def blocks(output: Seq[ResponseOutputItem]): Seq[ThinkingBlock] =
    output.zipWithIndex.flatMap((entry, index) =>
      Try(blocksOf(entry, output.drop(index + 1).find(!_.isReasoning))).getOrElse(Seq.empty)
    )

  private def blocksOf(entry: ResponseOutputItem, next: Option[ResponseOutputItem]): Seq[ThinkingBlock] =
    if !entry.isReasoning then Seq.empty
    else
      val item = entry.asReasoning()
      val summary = item.summary().asScala.toSeq.map(_.text()).filter(_.nonEmpty) match
        case Seq() => Seq.empty
        case texts => Seq(ThinkingBlock.Text(texts.mkString("\n\n")))
      val sealedItem =
        if next.isDefined && item.encryptedContent().toScala.exists(_.nonEmpty) then
          val before = next.filter(_.isFunctionCall).map(_.asFunctionCall().callId())
          val fields = Seq("item" -> ujson.read(ObjectMappers.jsonMapper().writeValueAsString(item))) ++
            before.map("before" -> ujson.Str(_))
          Seq(ThinkingBlock.Opaque(Provider, ujson.Obj.from(fields).render()))
        else Seq.empty
      summary ++ sealedItem

  /** Whether `block` is a reasoning item to send back, rather than a summary to show. */
  def isOpaque(block: ThinkingBlock): Boolean = block match
    case ThinkingBlock.Opaque(provider, _) => provider == Provider
    case _                                 => false

  /** One stored reasoning item: where it goes back, and the item itself. */
  final case class Stored(before: Option[String], item: ResponseReasoningItem)

  /** The reasoning items `blocks` holds for OpenAI, in order; anything else, or unreadable, is left out. */
  def stored(blocks: Seq[ThinkingBlock]): Seq[Stored] =
    blocks.flatMap {
      case ThinkingBlock.Opaque(provider, data) if provider == Provider => Try(read(data)).toOption.flatten
      case _                                                            => None
    }

  private def read(data: String): Option[Stored] =
    val envelope = ujson.read(data).obj
    val raw      = envelope("item")
    // not `validate()`: an item OpenAI returned that this SDK version cannot fully type (a newer `status`) is still sent back
    if !raw.obj.get("type").exists(_.strOpt.contains("reasoning")) || !raw.obj.get("id").exists(_.strOpt.isDefined) then
      None
    else
      val item = ObjectMappers.jsonMapper().readValue(raw.render(), classOf[ResponseReasoningItem])
      Some(Stored(envelope.get("before").flatMap(_.strOpt), item))
