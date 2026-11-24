package org.llm4s.llmconnect.streaming

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model._
import org.llm4s.llmconnect.provider.ToolCallRules
import org.llm4s.types.Result

import java.time.Instant
import scala.collection.mutable

/**
 * Accumulates streaming chunks into a complete response.
 * Handles content accumulation, tool call accumulation, thinking content, and token tracking.
 *
 * Created with `StreamingAccumulator(model)`, the model the completion reports. Feed it with
 * [[addChunk]], report the provider's usage with [[updateUsage]], and finish with [[toCompletion]].
 *
 * Mutable and not thread-safe: use one accumulator per stream, fed from the thread that reads
 * it. Tool calls are keyed by id, so every chunk of a streamed tool call - continuations
 * included - must carry its call's id; a tool-call chunk with an empty id is ignored. Clients
 * whose wire format identifies continuations only by index must map them back to the id first.
 */
@Stable
final class StreamingAccumulator private (model: String) {

  private val contentBuilder                       = new StringBuilder()
  private val thinkingBuilder                      = new StringBuilder()
  private var messageId: Option[String]            = None
  private var finishReason: Option[FinishReason]   = None
  private var promptTokens: Int                    = 0
  private var completionTokens: Int                = 0
  private var thinkingTokens: Int                  = 0
  private var cachedTokens: Option[Int]            = None
  private var cacheCreationTokens: Option[Int]     = None
  private var longCacheCreationTokens: Option[Int] = None
  private var reportedTotal: Option[Int]           = None

  // For accumulating partial tool calls, keyed by id. Insertion-ordered, so tool calls come
  // back in the order the stream first named them - the provider's index order - rather than
  // in hash order; callers that run tools sequentially depend on it (#1132).
  private val partialToolCalls = mutable.LinkedHashMap[String, PartialToolCall]()

  /**
   * Add a streaming chunk to the accumulator
   */
  def addChunk(chunk: StreamedChunk): Unit = {
    // Update message ID if provided
    if (chunk.id.nonEmpty) {
      messageId = Some(chunk.id)
    }

    // Accumulate main content
    chunk.content.foreach(contentBuilder.append)

    // Accumulate thinking content
    chunk.thinkingDelta.foreach(thinkingBuilder.append)

    // Handle tool calls
    chunk.toolCall.foreach { toolCall =>
      if (toolCall.id.nonEmpty) {
        // New tool call or update to existing
        val partial = partialToolCalls.getOrElseUpdate(
          toolCall.id,
          PartialToolCall(toolCall.id, toolCall.name, new StringBuilder())
        )

        // Update name if provided
        if (toolCall.name.nonEmpty) {
          partial.name = toolCall.name
        }

        // Accumulate arguments; preserve raw fragments when streaming partial JSON
        toolCall.arguments match {
          case ujson.Str(raw) if raw.nonEmpty =>
            partial.argumentsBuilder.append(raw)
          case obj: ujson.Obj if obj.obj.nonEmpty =>
            partial.argumentsBuilder.append(obj.render())
          case arr: ujson.Arr =>
            partial.argumentsBuilder.append(arr.render())
          case _ =>
        }
      }
    }

    // Update finish reason
    // an empty reason (none read) never replaces a real one, as in tracing
    chunk.finishReason.foreach(reason => if (reason.name.nonEmpty || finishReason.isEmpty) finishReason = Some(reason))
  }

  /**
   * Get the current accumulated content
   */
  def currentContent: String = contentBuilder.toString

  /**
   * Get the current accumulated thinking content
   */
  def currentThinking: Option[String] =
    if (thinkingBuilder.isEmpty) None else Some(thinkingBuilder.toString)

  /**
   * The tool calls so far, in the order each was first seen in the stream: a live view, for showing progress. A
   * call whose arguments are not yet a JSON object (still arriving, or broken) carries them as the raw `Str`; empty
   * is `{}`. Only [[toCompletion]] decides whether the calls stand (RFC 0009, Part 3).
   */
  def currentToolCalls: Seq[ToolCall] =
    partialToolCalls.values.map { p =>
      val raw = p.argumentsBuilder.toString
      // the one boundary parser: unparseable or nested too deeply stays the raw Str, never parsed (#1562)
      ToolCall(id = p.id, name = p.name, arguments = ToolCallRules.parseArguments(raw).getOrElse(ujson.Str(raw)))
    }.toSeq

  /**
   * The tool calls as the stream ended them, under RFC 0009's rules: a `Left` for any call when the output limit cut
   * the stream, for a call whose arguments are not a JSON object, and for a call with no arguments when the stream
   * did not end with `ToolCalls` or `Stop`.
   */
  private def finishedToolCalls(reason: FinishReason): Result[Seq[ToolCall]] = {
    val pending = partialToolCalls.values.toList
    // rule 3 first: a cut call's arguments are often cut JSON too, and the limit is the reason it broke
    val cut =
      Option.when(reason == FinishReason.Length && pending.nonEmpty)(ToolCallRules.cutByLimit(pending.map(_.name)))
    val partial = pending.foldLeft[Result[Vector[ToolCall]]](cut.toLeft(Vector.empty)) { (done, p) =>
      val raw = p.argumentsBuilder.toString
      for {
        calls     <- done
        _         <- if (raw.trim.isEmpty) ToolCallRules.blankArguments(p.name, reason) else Right(())
        arguments <- ToolCallRules.parseArguments(p.name, raw)
      } yield calls :+ ToolCall(id = p.id, name = p.name, arguments = arguments)
    }
    partial
  }

  /**
   * Check if accumulation is complete
   */
  def isComplete: Boolean = finishReason.isDefined

  /**
   * Check if there is any thinking content
   */
  def hasThinking: Boolean = thinkingBuilder.nonEmpty

  /**
   * Convert accumulated data to a Completion, stamped with the current time
   */
  def toCompletion: Result[Completion] = toCompletion(Instant.now())

  /**
   * Convert accumulated data to a Completion, stamped with the given creation time.
   * Pure given the accumulator's current state - split out from the no-arg overload
   * so the conversion itself is testable without a real clock.
   *
   * A `Left`, the malformed-tool-call [[org.llm4s.error.ProcessingError ProcessingError]], when a tool call breaks
   * one of RFC 0009's rules: its arguments are not a JSON object (cut, invalid, not an object, or nested more than
   * 512 levels deep); it has no arguments and the stream did not end with `ToolCalls` or `Stop`; or the output limit
   * cut the stream (`Length`) while it made tool calls. A broken call is never `{}` and never dropped.
   */
  def toCompletion(created: Instant): Result[Completion] = {
    // the last reason a chunk carried; none read is Other(""), never a Stop the provider didn't send
    val reason = finishReason.getOrElse(FinishReason.Other(""))
    finishedToolCalls(reason).map(completion(created, _, reason)).flatMap(ToolCallRules.checked)
  }

  private def completion(created: Instant, finalToolCalls: Seq[ToolCall], reason: FinishReason): Completion = {
    // the streamed thinking is unsigned text; a client whose provider signs its thinking blocks
    // replaces it on the message with the blocks it tracked
    val message = AssistantMessage(
      contentOpt = if (contentBuilder.isEmpty) None else Some(contentBuilder.toString),
      toolCalls = finalToolCalls
    ).withThinking(thinkingBuilder.toString)

    val thinkingTokensOpt = if (thinkingTokens > 0) Some(thinkingTokens) else None
    val anyCacheTokens    = cachedTokens.exists(_ > 0) || cacheCreationTokens.exists(_ > 0)
    val usage = if (promptTokens > 0 || completionTokens > 0 || thinkingTokens > 0 || anyCacheTokens) {
      Some(
        TokenUsage(
          promptTokens = promptTokens,
          completionTokens = completionTokens,
          totalTokens = reportedTotal.getOrElse(promptTokens + completionTokens + thinkingTokens),
          thinkingTokens = thinkingTokensOpt,
          cachedTokens = cachedTokens,
          cacheCreationTokens = cacheCreationTokens,
          longCacheCreationTokens = longCacheCreationTokens
        )
      )
    } else None

    Completion(
      id = messageId.getOrElse(""),
      created = created,
      content = contentBuilder.toString(),
      model = model,
      message = message,
      usage = usage,
      finishReason = reason
    )
  }

  /**
   * Merge a provider's usage report into the accumulated usage, every [[org.llm4s.llmconnect.model.TokenUsage]] field
   * included, so a streamed [[org.llm4s.llmconnect.model.Completion]] reports what a non-streaming one does.
   *
   * Call it once per usage report the stream carries. Each field follows the latest report that
   * states it: `promptTokens` and `completionTokens` when positive, the `Option` fields
   * (`thinkingTokens`, `cachedTokens`, `cacheCreationTokens`, `longCacheCreationTokens`) when defined, and `totalTokens`
   * when positive. A field a report leaves at zero or `None` keeps its earlier value, so a
   * provider that splits its usage across events (input and cache counts first, output last)
   * can pass each part as it arrives. When no report states a total, the completion's total is
   * prompt plus completion plus thinking tokens.
   *
   * A report's `completionTokens` of zero is read as not stated, so the earlier value is kept, although a
   * reasoning-only reply can report zero (its client takes the reasoning tokens out of the completion). The
   * clients that take them out report usage once per stream, so the earlier value is the initial zero.
   *
   * @param usage the usage as the provider reported it, in llm4s's terms: `thinkingTokens` the
   *              reasoning tokens, `cachedTokens` the prompt tokens read from the provider's
   *              prompt cache, `cacheCreationTokens` those written to it
   */
  def updateUsage(usage: TokenUsage): Unit = {
    if (usage.promptTokens > 0) promptTokens = usage.promptTokens
    if (usage.completionTokens > 0) completionTokens = usage.completionTokens
    usage.thinkingTokens.foreach(t => thinkingTokens = t)
    usage.cachedTokens.foreach(c => cachedTokens = Some(c))
    usage.cacheCreationTokens.foreach(c => cacheCreationTokens = Some(c))
    usage.longCacheCreationTokens.foreach(c => longCacheCreationTokens = Some(c))
    if (usage.totalTokens > 0) reportedTotal = Some(usage.totalTokens)
  }

  /**
   * Clear the accumulator state
   */
  def clear(): Unit = {
    contentBuilder.clear()
    thinkingBuilder.clear()
    partialToolCalls.clear()
    messageId = None
    finishReason = None
    promptTokens = 0
    completionTokens = 0
    thinkingTokens = 0
    cachedTokens = None
    cacheCreationTokens = None
    longCacheCreationTokens = None
    reportedTotal = None
  }

  /**
   * Helper class for partial tool call accumulation
   */
  private case class PartialToolCall(
    id: String,
    var name: String,
    argumentsBuilder: StringBuilder
  )
}

/**
 * Factory for creating accumulators
 */
object StreamingAccumulator {

  /**
   * A new, empty accumulator.
   *
   * @param model the model the completion reports as `Completion.model`: the model the request named, or
   *              `""` where the caller does not know it
   */
  def apply(model: String): StreamingAccumulator = new StreamingAccumulator(model)

}
