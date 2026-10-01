package org.llm4s.llmconnect.streaming

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.collection.mutable
import scala.util.Try

/**
 * Accumulates streaming chunks into a complete response.
 * Handles content accumulation, tool call accumulation, thinking content, and token tracking.
 *
 * Mutable and not thread-safe: use one accumulator per stream, fed from the thread that reads
 * it. Tool calls are keyed by id, so every chunk of a streamed tool call - continuations
 * included - must carry its call's id; a tool-call chunk with an empty id is ignored. Clients
 * whose wire format identifies continuations only by index must map them back to the id first.
 */
final class StreamingAccumulator private () {

  private val contentBuilder               = new StringBuilder()
  private val thinkingBuilder              = new StringBuilder()
  private val toolCalls                    = mutable.ArrayBuffer[ToolCall]()
  private var messageId: Option[String]    = None
  private var finishReason: Option[String] = None
  private var promptTokens: Int            = 0
  private var completionTokens: Int        = 0
  private var thinkingTokens: Int          = 0

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
      val args =
        if (p.argumentsBuilder.isEmpty) ujson.Obj()
        else {
          val raw = p.argumentsBuilder.toString
          Try(ujson.read(raw)).getOrElse(ujson.Str(raw))
        }
      ToolCall(
        id = p.id,
        name = p.name,
        arguments = args
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
  def toCompletion: Result[Completion] = toCompletion(System.currentTimeMillis() / 1000)

  /**
   * Convert accumulated data to a Completion, stamped with the given creation time.
   * Pure given the accumulator's current state - split out from the no-arg overload
   * so the conversion itself is testable without a real clock.
   */
  def toCompletion(created: Long): Result[Completion] = {
    val finalToolCalls = currentToolCalls

    val message = AssistantMessage(
      contentOpt = if (contentBuilder.isEmpty) None else Some(contentBuilder.toString),
      toolCalls = finalToolCalls
    )

    val thinkingTokensOpt = if (thinkingTokens > 0) Some(thinkingTokens) else None
    val usage = if (promptTokens > 0 || completionTokens > 0 || thinkingTokens > 0) {
      Some(
        TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens + thinkingTokens, thinkingTokensOpt)
      )
    } else None

    val thinking = currentThinking

    Right(
      Completion(
        id = messageId.getOrElse(""),
        created = created,
        content = contentBuilder.toString(),
        model = "unknown",
        message = message,
        usage = usage,
        thinking = thinking
      )
    )
  }

  /**
   * Update token counts
   */
  def updateTokens(prompt: Int, completion: Int): Unit = {
    promptTokens = prompt
    completionTokens = completion
  }

  /**
   * Update token counts including thinking tokens
   */
  def updateTokensWithThinking(prompt: Int, completion: Int, thinking: Int): Unit = {
    promptTokens = prompt
    completionTokens = completion
    thinkingTokens = thinking
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
