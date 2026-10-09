package org.llm4s.llmconnect.model

import org.llm4s.annotation.Stable

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
 * @param estimatedCost Optional estimated cost of this completion in USD.
 *                      Computed from token usage and model pricing when available.
 * @param citations The sources the model cited in its answer, when the provider reports them
 *                  (see [[Citation]]); empty for a model that does not search or does not cite,
 *                  and for a completion assembled from streamed chunks, which carry none.
 */
@Stable
final case class Completion private (
  id: String,
  created: Long,
  content: String,
  model: String,
  message: AssistantMessage,
  toolCalls: List[ToolCall],
  usage: Option[TokenUsage],
  estimatedCost: Option[Double],
  citations: List[Citation]
) {
  def withId(id: String): Completion                               = copy(id = id)
  def withCreated(created: Long): Completion                       = copy(created = created)
  def withContent(content: String): Completion                     = copy(content = content)
  def withModel(model: String): Completion                         = copy(model = model)
  def withMessage(message: AssistantMessage): Completion           = copy(message = message)
  def withToolCalls(toolCalls: List[ToolCall]): Completion         = copy(toolCalls = toolCalls)
  def withUsage(usage: TokenUsage): Completion                     = copy(usage = Some(usage))
  def withUsage(usage: Option[TokenUsage]): Completion             = copy(usage = usage)
  def withEstimatedCost(estimatedCost: Double): Completion         = copy(estimatedCost = Some(estimatedCost))
  def withEstimatedCost(estimatedCost: Option[Double]): Completion = copy(estimatedCost = estimatedCost)
  def withCitations(citations: List[Citation]): Completion         = copy(citations = citations)

  /**
   * Extract content as text (for compatibility)
   */
  def asText: String = content

  /**
   * Check if completion contains tool calls
   */
  def hasToolCalls: Boolean = toolCalls.nonEmpty

  /**
   * Check if the provider reported any [[Citation]] for this completion
   */
  def hasCitations: Boolean = citations.nonEmpty

  /**
   * The text of the model's thinking/reasoning, when the provider reports it: the thinking
   * text of [[message]] (see [[AssistantMessage.thinking]]), which is where clients put it so
   * that it stays in the conversation history. Set it with `withMessage(message.withThinking(...))`.
   */
  def thinking: Option[String] = message.thinkingText

  /**
   * Whether the completion carries thinking, text or redacted (see [[AssistantMessage.hasThinking]]).
   * A completion whose only reasoning is redacted has thinking but no [[thinking]] text.
   */
  def hasThinking: Boolean = message.hasThinking

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

object Completion {

  /** Creates a [[Completion]]. Named arguments are the supported way to construct one. */
  def apply(
    id: String,
    created: Long,
    content: String,
    model: String,
    message: AssistantMessage,
    toolCalls: List[ToolCall] = List.empty,
    usage: Option[TokenUsage] = None,
    estimatedCost: Option[Double] = None,
    citations: List[Citation] = List.empty
  ): Completion =
    new Completion(id, created, content, model, message, toolCalls, usage, estimatedCost, citations)
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
@Stable
final case class TokenUsage private (
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int,
  thinkingTokens: Option[Int],
  cachedTokens: Option[Int],
  cacheCreationTokens: Option[Int]
) {
  def withPromptTokens(promptTokens: Int): TokenUsage             = copy(promptTokens = promptTokens)
  def withCompletionTokens(completionTokens: Int): TokenUsage     = copy(completionTokens = completionTokens)
  def withTotalTokens(totalTokens: Int): TokenUsage               = copy(totalTokens = totalTokens)
  def withThinkingTokens(thinkingTokens: Int): TokenUsage         = copy(thinkingTokens = Some(thinkingTokens))
  def withThinkingTokens(thinkingTokens: Option[Int]): TokenUsage = copy(thinkingTokens = thinkingTokens)
  def withCachedTokens(cachedTokens: Int): TokenUsage             = copy(cachedTokens = Some(cachedTokens))
  def withCachedTokens(cachedTokens: Option[Int]): TokenUsage     = copy(cachedTokens = cachedTokens)
  def withCacheCreationTokens(cacheCreationTokens: Int): TokenUsage =
    copy(cacheCreationTokens = Some(cacheCreationTokens))
  def withCacheCreationTokens(cacheCreationTokens: Option[Int]): TokenUsage =
    copy(cacheCreationTokens = cacheCreationTokens)

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

object TokenUsage {

  /** Creates a [[TokenUsage]]. Named arguments are the supported way to construct one. */
  def apply(
    promptTokens: Int,
    completionTokens: Int,
    totalTokens: Int,
    thinkingTokens: Option[Int] = None,
    cachedTokens: Option[Int] = None,
    cacheCreationTokens: Option[Int] = None
  ): TokenUsage =
    new TokenUsage(promptTokens, completionTokens, totalTokens, thinkingTokens, cachedTokens, cacheCreationTokens)
}

/**
 * Token usage statistics for an embedding request.
 *
 * @param promptTokens Number of tokens in the input text(s).
 * @param totalTokens Total tokens used (same as promptTokens for embeddings).
 */
@Stable
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
@Stable
final case class StreamedChunk private (
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall],
  finishReason: Option[String],
  thinkingDelta: Option[String]
) {
  def withId(id: String): StreamedChunk                               = copy(id = id)
  def withContent(content: String): StreamedChunk                     = copy(content = Some(content))
  def withContent(content: Option[String]): StreamedChunk             = copy(content = content)
  def withToolCall(toolCall: ToolCall): StreamedChunk                 = copy(toolCall = Some(toolCall))
  def withToolCall(toolCall: Option[ToolCall]): StreamedChunk         = copy(toolCall = toolCall)
  def withFinishReason(finishReason: String): StreamedChunk           = copy(finishReason = Some(finishReason))
  def withFinishReason(finishReason: Option[String]): StreamedChunk   = copy(finishReason = finishReason)
  def withThinkingDelta(thinkingDelta: String): StreamedChunk         = copy(thinkingDelta = Some(thinkingDelta))
  def withThinkingDelta(thinkingDelta: Option[String]): StreamedChunk = copy(thinkingDelta = thinkingDelta)

  /**
   * Check if this chunk contains thinking content.
   */
  def hasThinking: Boolean = thinkingDelta.exists(_.nonEmpty)

  /**
   * Check if this chunk contains main content.
   */
  def hasContent: Boolean = content.exists(_.nonEmpty)
}

object StreamedChunk {

  /** Creates a [[StreamedChunk]]. Named arguments are the supported way to construct one. */
  def apply(
    id: String,
    content: Option[String],
    toolCall: Option[ToolCall] = None,
    finishReason: Option[String] = None,
    thinkingDelta: Option[String] = None
  ): StreamedChunk =
    new StreamedChunk(id, content, toolCall, finishReason, thinkingDelta)
}

/**
 * Represents a streaming chunk of completion data
 */
@Stable
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
@Stable
final case class ChunkDelta(
  content: Option[String] = None,
  role: Option[String] = None,
  toolCalls: List[ToolCall] = List.empty
)

object ChunkDelta {
  def empty: ChunkDelta = ChunkDelta()
}
