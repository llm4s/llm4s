package org.llm4s.llmconnect.streaming

import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

import scala.collection.mutable

/**
 * Accumulates streaming chunks into a complete response.
 * Handles content accumulation, tool call accumulation, and token tracking.
 */
class StreamingAccumulator {

  private val contentBuilder               = new StringBuilder()
  private val toolCalls                    = mutable.ArrayBuffer[ToolCall]()
  private var messageId: Option[String]    = None
  private var finishReason: Option[String] = None
  private var promptTokens: Int            = 0
  private var completionTokens: Int        = 0

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

    // Accumulate content
    chunk.content.foreach(contentBuilder.append)

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

        // Accumulate arguments
        if (toolCall.arguments != ujson.Null) {
          partial.argumentsBuilder.append(toolCall.arguments.render())
        }
      }
    }

    // Update finish reason
    chunk.finishReason.foreach(reason => finishReason = Some(reason))
  }

  /**
   * Get the current accumulated content
   */
  def getCurrentContent: String = contentBuilder.toString

  /**
   * Get the current tool calls
   */
  def getCurrentToolCalls: Seq[ToolCall] = {
    val completed = toolCalls.toSeq
    val partial = partialToolCalls.values.map { p =>
      ToolCall(
        id = p.id,
        name = p.name,
        arguments = if (p.argumentsBuilder.isEmpty) ujson.Null else ujson.read(p.argumentsBuilder.toString)
      )
    }.toSeq
    completed ++ partial
  }

  /**
   * Check if accumulation is complete
   */
  def isComplete: Boolean = finishReason.isDefined

  /**
   * Convert accumulated data to a Completion
   */
  def toCompletion: Result[Completion] = {
    val finalToolCalls = getCurrentToolCalls

    val message = AssistantMessage(
      contentOpt = if (contentBuilder.isEmpty) None else Some(contentBuilder.toString),
      toolCalls = finalToolCalls
    )

    val usage = if (promptTokens > 0 || completionTokens > 0) {
      Some(TokenUsage(promptTokens, completionTokens, promptTokens + completionTokens))
    } else None

    Right(
      Completion(
        id = messageId.getOrElse(""),
        created = System.currentTimeMillis() / 1000,
        content = contentBuilder.toString(),
        model = "unknown",
        message = message,
        usage = usage
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
   * Clear the accumulator state
   */
  def clear(): Unit = {
    contentBuilder.clear()
    toolCalls.clear()
    partialToolCalls.clear()
    messageId = None
    finishReason = None
    promptTokens = 0
    completionTokens = 0
  }

  /**
   * Get a snapshot of the current state
   */
  def snapshot(): AccumulatorSnapshot =
    AccumulatorSnapshot(
      content = getCurrentContent,
      toolCalls = getCurrentToolCalls,
      messageId = messageId,
      finishReason = finishReason,
      promptTokens = promptTokens,
      completionTokens = completionTokens
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
  toolCalls: Seq[ToolCall],
  messageId: Option[String],
  finishReason: Option[String],
  promptTokens: Int,
  completionTokens: Int
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
