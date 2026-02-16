package org.llm4s.llmconnect.streaming

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.collection.mutable
import scala.util.Try

/**
 * Accumulates streaming chunks into a complete response.
 * Handles content accumulation, tool call accumulation, thinking content, and token tracking.
 */
class StreamingAccumulator {

  private val contentBuilder               = new StringBuilder()
  private val thinkingBuilder              = new StringBuilder()
  private val toolCalls                    = mutable.ArrayBuffer[ToolCall]()
  private var messageId: Option[String]    = None
  private var finishReason: Option[String] = None
  private var promptTokens: Int            = 0
  private var completionTokens: Int        = 0
  private var thinkingTokens: Int          = 0

  // For accumulating partial tool calls
  private val partialToolCalls = mutable.Map[String, PartialToolCall]()

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
  def getCurrentContent: String = contentBuilder.toString

  /**
   * Get the current accumulated thinking content
   */
  def getCurrentThinking: Option[String] =
    if (thinkingBuilder.isEmpty) None else Some(thinkingBuilder.toString)

  /**
   * Get the current tool calls
   */
  def getCurrentToolCalls: Seq[ToolCall] = {
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
   * Convert accumulated data to a Completion
   */
  def toCompletion: Result[Completion] = {
    val finalToolCalls = getCurrentToolCalls

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

    val thinking = getCurrentThinking

    Right(
      Completion(
        id = messageId.getOrElse(""),
        created = System.currentTimeMillis() / 1000,
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
   * Get a snapshot of the current state
   */
  def snapshot(): AccumulatorSnapshot =
    AccumulatorSnapshot(
      content = getCurrentContent,
      thinking = getCurrentThinking,
      toolCalls = getCurrentToolCalls,
      messageId = messageId,
      finishReason = finishReason,
      promptTokens = promptTokens,
      completionTokens = completionTokens,
      thinkingTokens = thinkingTokens
    )

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
 * Snapshot of accumulator state
 */
case class AccumulatorSnapshot(
  content: String,
  thinking: Option[String],
  toolCalls: Seq[ToolCall],
  messageId: Option[String],
  finishReason: Option[String],
  promptTokens: Int,
  completionTokens: Int,
  thinkingTokens: Int = 0
)

/**
 * Factory for creating accumulators
 */
object StreamingAccumulator {

  /**
   * Create a new accumulator instance
   */
  def create(): StreamingAccumulator = new StreamingAccumulator()

  /**
   * Create an accumulator with initial state
   */
  def withInitialState(
    messageId: Option[String] = None,
    promptTokens: Int = 0
  ): StreamingAccumulator = {
    val accumulator = new StreamingAccumulator()
    messageId.foreach(id => accumulator.addChunk(StreamedChunk(id, None, None, None)))
    if (promptTokens > 0) {
      accumulator.updateTokens(promptTokens, 0)
    }
    accumulator
  }
}
