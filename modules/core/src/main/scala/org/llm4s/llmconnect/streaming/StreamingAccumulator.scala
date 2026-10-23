package org.llm4s.llmconnect.streaming

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import java.time.Instant
import scala.collection.mutable

/**
 * Accumulates streaming chunks into a complete response.
 * Handles content accumulation, tool call accumulation, thinking content, and token tracking.
 *
 * Mutable and not thread-safe: use one accumulator per stream, fed from the thread that reads
 * it. Tool calls are keyed by id, so every chunk of a streamed tool call - continuations
 * included - must carry its call's id; a tool-call chunk with an empty id is ignored. Clients
 * whose wire format identifies continuations only by index must map them back to the id first.
 */
@Stable
final class StreamingAccumulator private () {

  private val contentBuilder                   = new StringBuilder()
  private val thinkingBuilder                  = new StringBuilder()
  private val toolCalls                        = mutable.ArrayBuffer[ToolCall]()
  private var messageId: Option[String]        = None
  private var finishReason: Option[String]     = None
  private var promptTokens: Int                = 0
  private var completionTokens: Int            = 0
  private var thinkingTokens: Int              = 0
  private var cachedTokens: Option[Int]        = None
  private var cacheCreationTokens: Option[Int] = None
  private var reportedTotal: Option[Int]       = None

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
    chunk.finishReason.foreach(reason => finishReason = Some(reason))
  }

  /**
   * Add thinking content delta directly
   */
  def addThinkingDelta(delta: String): Unit =
    thinkingBuilder.append(delta)

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
   * Get the current tool calls, in the order each was first seen in the stream
   */
  def currentToolCalls: Seq[ToolCall] = {
    val completed = toolCalls.toSeq
    val partial = partialToolCalls.values.map { p =>
      // the one boundary parser: empty -> {}, unparseable or nested too deeply -> the raw Str (#1562)
      ToolCall(
        id = p.id,
        name = p.name,
        arguments = StreamingToolArgumentParser.parse(p.argumentsBuilder.toString)
      )
    }.toSeq
    completed ++ partial
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
   */
  def toCompletion(created: Instant): Result[Completion] = {
    val finalToolCalls = currentToolCalls

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
          cacheCreationTokens = cacheCreationTokens
        )
      )
    } else None

    Right(
      Completion(
        id = messageId.getOrElse(""),
        created = created,
        content = contentBuilder.toString(),
        model = "unknown",
        message = message,
        usage = usage
      )
    )
  }

  /**
   * Update the prompt and completion token counts.
   *
   * The completion's total becomes their sum plus any thinking tokens; cache counts set by
   * [[updateUsage]] are kept. Use [[updateUsage]] when the provider reports more than these two.
   */
  def updateTokens(prompt: Int, completion: Int): Unit = {
    promptTokens = prompt
    completionTokens = completion
    reportedTotal = None
  }

  /**
   * Update token counts including thinking tokens.
   *
   * The completion's total becomes their sum; cache counts set by [[updateUsage]] are kept.
   */
  def updateTokensWithThinking(prompt: Int, completion: Int, thinking: Int): Unit = {
    promptTokens = prompt
    completionTokens = completion
    thinkingTokens = thinking
    reportedTotal = None
  }

  /**
   * Merge a provider's usage report into the accumulated usage, every [[TokenUsage]] field
   * included, so a streamed [[Completion]] reports what a non-streaming one does.
   *
   * Call it once per usage report the stream carries. Each field follows the latest report that
   * states it: `promptTokens` and `completionTokens` when positive, the `Option` fields
   * (`thinkingTokens`, `cachedTokens`, `cacheCreationTokens`) when defined, and `totalTokens`
   * when positive. A field a report leaves at zero or `None` keeps its earlier value, so a
   * provider that splits its usage across events (input and cache counts first, output last)
   * can pass each part as it arrives. When no report states a total, the completion's total is
   * prompt plus completion plus thinking tokens.
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
    if (usage.totalTokens > 0) reportedTotal = Some(usage.totalTokens)
  }

  /**
   * Clear the accumulator state
   */
  def clear(): Unit = {
    contentBuilder.clear()
    thinkingBuilder.clear()
    toolCalls.clear()
    partialToolCalls.clear()
    messageId = None
    finishReason = None
    promptTokens = 0
    completionTokens = 0
    thinkingTokens = 0
    cachedTokens = None
    cacheCreationTokens = None
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
   * Create a new accumulator instance
   */
  def create(): StreamingAccumulator = new StreamingAccumulator()

}
