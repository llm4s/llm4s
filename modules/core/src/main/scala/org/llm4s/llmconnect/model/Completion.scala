package org.llm4s.llmconnect.model

/**
 * Represents a completion response from an LLM.
 * This includes the ID, creation timestamp, the assistant's message, and optional token usage statistics.
 *
 * @param id Unique identifier for the completion.
 * @param created Timestamp of when the completion was created.
 * @param content The main content of the response.
 * @param model The model that generated this completion.
 * @param message The assistant's message in response to the user's input.
 * @param toolCalls List of tool calls made by the assistant.
 * @param usage Optional token usage statistics for the completion.
 * @param thinking Optional thinking/reasoning content from extended thinking models.
 *                 Present when using reasoning modes with Claude or o1/o3 models.
 * @param estimatedCost Optional estimated cost of this completion in USD.
 *                      Computed from token usage and model pricing when available.
 */
case class Completion(
  id: String,
  created: Long,
  content: String,
  model: String,
  message: AssistantMessage,
  toolCalls: List[ToolCall] = List.empty,
  usage: Option[TokenUsage] = None,
  thinking: Option[String] = None,
  estimatedCost: Option[Double] = None
) {

  /**
   * Extract content as text (for compatibility)
   */
  def asText: String = content

  /**
   * Check if completion contains tool calls
   */
  def hasToolCalls: Boolean = toolCalls.nonEmpty

  /**
   * Check if completion includes thinking/reasoning content.
   */
  def hasThinking: Boolean = thinking.exists(_.nonEmpty)

  /**
   * Get the full response including thinking content (if available).
   *
   * Returns thinking wrapped in XML-style tags followed by the main content.
   * Useful for logging or debugging the model's reasoning process.
   */
  def fullContent: String = thinking match {
    case Some(t) if t.nonEmpty => s"<thinking>\n$t\n</thinking>\n\n$content"
    case _                     => content
  }
}

/**
 * Token usage statistics for a completion request.
 *
 * @param promptTokens Number of tokens in the prompt (input).
 * @param completionTokens Number of tokens in the completion (output).
 * @param totalTokens Total tokens (prompt + completion).
 * @param thinkingTokens Optional number of tokens used for thinking/reasoning.
 *                       Present when using reasoning modes with Claude or o1/o3 models.
 *                       These tokens count toward billing but are separate from completion tokens.
 * @param cachedTokens Optional number of tokens served from the provider's prompt cache (cache read).
 *                     When present, these tokens are billed at the cheaper cache-read rate.
 * @param cacheCreationTokens Optional number of tokens written into the provider's prompt cache.
 *                            When present, these tokens are billed at the cache-creation rate,
 *                            which is typically higher than the normal input rate.
 */
case class TokenUsage(
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int,
  thinkingTokens: Option[Int] = None,
  cachedTokens: Option[Int] = None,
  cacheCreationTokens: Option[Int] = None
) {

  /**
   * Total output tokens including thinking.
   *
   * For billing purposes, thinking tokens are typically billed at the same rate as output tokens.
   */
  def totalOutputTokens: Int = completionTokens + thinkingTokens.getOrElse(0)

  /**
   * Check if thinking tokens were used.
   */
  def hasThinkingTokens: Boolean = thinkingTokens.exists(_ > 0)
}

/**
 * Token usage statistics for an embedding request.
 *
 * @param promptTokens Number of tokens in the input text(s).
 * @param totalTokens Total tokens used (same as promptTokens for embeddings).
 */
case class EmbeddingUsage(
  promptTokens: Int,
  totalTokens: Int
)

/**
 * Represents a streamed chunk of completion data.
 *
 * @param id Unique identifier for the stream.
 * @param content Optional text content delta.
 * @param toolCall Optional tool call information.
 * @param finishReason Optional reason for stream completion.
 * @param thinkingDelta Optional thinking/reasoning content delta.
 *                      Present when streaming extended thinking content.
 */
case class StreamedChunk(
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall] = None,
  finishReason: Option[String] = None,
  thinkingDelta: Option[String] = None
) {

  /**
   * Check if this chunk contains thinking content.
   */
  def hasThinking: Boolean = thinkingDelta.exists(_.nonEmpty)

  /**
   * Check if this chunk contains main content.
   */
  def hasContent: Boolean = content.exists(_.nonEmpty)
}

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
