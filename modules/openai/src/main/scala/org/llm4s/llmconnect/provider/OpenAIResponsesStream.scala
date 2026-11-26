package org.llm4s.llmconnect.provider

import com.openai.core.JsonField
import com.openai.models.responses.{ Response, ResponseStreamEvent }
import org.llm4s.llmconnect.model.{ FinishReason, StreamedChunk, ToolCall, ToolCallId }

import scala.collection.mutable
import scala.jdk.OptionConverters._
import scala.util.Try

/**
 * The events of one Responses API stream (`stream: true`) as [[org.llm4s.llmconnect.model.StreamedChunk]]s, delivered
 * to `onChunk` as they arrive, and what the stream must remember to end: the terminal event's `Response`, from which
 * the client builds the final completion exactly as `complete` does, or the error of an `error` event.
 *
 * Text and refusal deltas are `contentDelta`s, reasoning-summary deltas `thinkingDelta`s (a blank line opens each
 * summary part after the first of its item, as the final completion joins them). A tool call opens with its
 * `function_call` item (`response.output_item.added`) and continues with argument fragments that name only the item,
 * so the stream keeps each item's call id and name and puts them on every fragment: `StreamingAccumulator` keys calls
 * by id. A fragment is passed on verbatim as a string, as the Chat Completions stream's are. An event the SDK cannot
 * read, or one not mapped, delivers nothing; nothing is delivered after a terminal or `error` event.
 *
 * Not thread-safe: one instance reads one stream, on the thread that reads it.
 */
final private[provider] class OpenAIResponsesStream(onChunk: StreamedChunk => Unit):

  private val callsByItem  = mutable.Map.empty[String, (String, String)] // item_id -> (call_id, name)
  private val callsByIndex = mutable.Map.empty[Long, (String, String)]   // output_index -> (call_id, name)
  private val partsBegun   = mutable.Set.empty[String]                   // item ids whose summary has a part begun
  private var responseId   = ""
  private var ended        = Option.empty[Response]
  private var failure      = Option.empty[ujson.Value]

  /** Delivers the chunks `event` stands for, and remembers a terminal event. */
  def accept(event: ResponseStreamEvent): Unit =
    // read inside `Try`, so an event the SDK cannot read is skipped; `onChunk` is called outside it, so its own
    // failure is not swallowed
    if ended.isEmpty && failure.isEmpty then Try(chunksOf(event)).getOrElse(Seq.empty).foreach(onChunk)

  /** The terminal event's response: `response.completed`, `response.incomplete` or `response.failed`. */
  def terminal: Option[Response] = ended

  /** The error of an `error` event, when one arrived: an object of its `code` and `message`. */
  def streamError: Option[ujson.Value] = failure

  /** Delivers the closing chunk carrying `reason`; call once, after the final completion is known. */
  def finish(reason: FinishReason): Unit =
    onChunk(StreamedChunk(id = responseId, contentDelta = None, toolCallDelta = None, finishReason = Some(reason)))

  private def chunksOf(event: ResponseStreamEvent): Seq[StreamedChunk] =
    if event.isOutputTextDelta then content(known(event.asOutputTextDelta()._delta()))
    else if event.isRefusalDelta then content(known(event.asRefusalDelta()._delta()))
    else if event.isReasoningSummaryTextDelta then
      known(event.asReasoningSummaryTextDelta()._delta()).filter(_.nonEmpty).map(d => chunk(thinking = Some(d))).toSeq
    else if event.isReasoningSummaryPartAdded then
      // the final completion joins one item's summary parts with a blank line (and two items' summaries with none)
      val item = known(event.asReasoningSummaryPartAdded()._itemId()).getOrElse("")
      if partsBegun.add(item) then Seq.empty else Seq(chunk(thinking = Some("\n\n")))
    else if event.isOutputItemAdded then opened(event)
    else if event.isFunctionCallArgumentsDelta then
      val delta = event.asFunctionCallArgumentsDelta()
      val call = known(delta._itemId())
        .flatMap(callsByItem.get)
        .orElse(known(delta._outputIndex()).flatMap(i => callsByIndex.get(i.longValue)))
      call
        .zip(known(delta._delta()).filter(_.nonEmpty))
        .map { case ((id, name), fragment) =>
          chunk(call = Some(ToolCall(ToolCallId(id), name, ujson.Str(fragment))))
        }
        .toSeq
    else
      if event.isCreated then known(event.asCreated()._response()).foreach(started)
      else if event.isCompleted then ended = known(event.asCompleted()._response())
      else if event.isIncomplete then ended = known(event.asIncomplete()._response())
      else if event.isFailed then ended = known(event.asFailed()._response())
      else if event.isError then
        val error = event.asError()
        failure = Some(
          ujson.Obj.from(
            Seq(known(error._code()).map("code" -> _), known(error._message()).map("message" -> _)).flatten
              .map((k, v) => k -> ujson.Str(v))
          )
        )
      ended.foreach(started)
      Seq.empty

  /** A `function_call` item's opening delta: its call id and name, and no arguments yet (the empty-object sentinel). */
  private def opened(event: ResponseStreamEvent): Seq[StreamedChunk] =
    val added = event.asOutputItemAdded()
    known(added._item()).filter(_.isFunctionCall).map(_.asFunctionCall()).toSeq.flatMap { item =>
      known(item._callId()).filter(_.nonEmpty).map { id =>
        val name = known(item._name()).getOrElse("")
        known(item._id()).foreach(callsByItem.update(_, (id, name)))
        known(added._outputIndex()).foreach(i => callsByIndex.update(i.longValue, (id, name)))
        val arguments = known(item._arguments()).filter(_.nonEmpty).fold[ujson.Value](ujson.Obj())(ujson.Str(_))
        chunk(call = Some(ToolCall(ToolCallId(id), name, arguments)))
      }
    }

  private def started(response: Response): Unit =
    known(response._id()).filter(_.nonEmpty).foreach(responseId = _)

  private def content(delta: Option[String]): Seq[StreamedChunk] =
    delta.filter(_.nonEmpty).map(d => chunk(content = Some(d))).toSeq

  private def chunk(
    content: Option[String] = None,
    thinking: Option[String] = None,
    call: Option[ToolCall] = None
  ): StreamedChunk =
    StreamedChunk(
      id = responseId,
      contentDelta = content,
      toolCallDelta = call,
      finishReason = None,
      thinkingDelta = thinking
    )

  private def known[T](field: JsonField[T]): Option[T] = field.asKnown().toScala
