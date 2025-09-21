package org.llm4s.llmconnect.model

/**
 * Represents a completion response from an LLM.
 *  This includes the ID, creation timestamp, the assistant's message, and optional token usage statistics.
 *
 * @param id Unique identifier for the completion.
 * @param created Timestamp of when the completion was created.
 * @param message The assistant's message in response to the user's input.
 * @param usage Optional token usage statistics for the completion.
 */
case class Completion(
  id: String,
  created: Long,
  content: String,
  model: String,
  message: AssistantMessage,
  toolCalls: List[ToolCall] = List.empty,
  usage: Option[TokenUsage] = None
) {

  /**
   * Extract content as text (for compatibility)
   */
  def asText: String = content

  /**
   * Check if completion contains tool calls
   */
  def hasToolCalls: Boolean = toolCalls.nonEmpty
}

case class TokenUsage(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int
)

case class StreamedChunk(
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall] = None,
  finishReason: Option[String] = None
)

/**
 * Represents a streaming chunk of completion data
 */
final case class CompletionChunk(
  id: String,
  content: Option[String] = None,
  toolCall: Option[ToolCall] = None,
  finishReason: Option[String] = None,
  delta: ChunkDelta = ChunkDelta.empty
) {

  /**
   * Check if this chunk represents the end of the stream
   */
  def isComplete: Boolean = finishReason.isDefined

  /**
   * Extract text content from chunk
   */
  def asText: String = content.getOrElse("")
}

/**
 * Delta information for streaming chunks
 */
final case class ChunkDelta(
  content: Option[String] = None,
  role: Option[String] = None,
  toolCalls: List[ToolCall] = List.empty
)

object ChunkDelta {
  def empty: ChunkDelta = ChunkDelta()
}
