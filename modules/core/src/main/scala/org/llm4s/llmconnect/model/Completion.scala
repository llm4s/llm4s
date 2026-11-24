package org.llm4s.llmconnect.model

import org.llm4s.types.ProviderModelTypes.ModelName
import org.llm4s.annotation.Stable

import java.time.Instant

/**
 * Represents a completion response from an LLM.
 * This includes the ID, creation timestamp, the assistant's message, and optional token usage statistics.
 *
 * @param id Unique identifier for the completion.
 * @param created When the completion was created. Providers that report a creation time (the
 *                OpenAI-format `created` field, epoch seconds) are converted at the wire boundary;
 *                providers that report none stamp the time the response was received.
 * @param model The model that generated this completion.
 * @param message The assistant's message in response to the user's input: its text is [[content]] and its tool
 *                calls are [[toolCalls]]. They are read from the message, never stored beside it, so they cannot
 *                disagree with it; change them with `withMessage(message.withContent(...))`.
 * @param usage Optional token usage statistics for the completion.
 * @param estimatedCost Optional estimated cost of this completion in USD, a `BigDecimal` so that it is exact and
 *                      sums without drift. Computed from token usage and model pricing when available.
 * @param citations The sources the model cited in its answer, when the provider reports them
 *                  (see [[Citation]]); empty for a model that does not search or does not cite,
 *                  and for a completion assembled from streamed chunks, which carry none.
 * @param adjustments The requested options the client reports it could not honour as asked, each
 *                    dropped, adjusted or not needed (see [[RequestAdjustment]]). Streamed and
 *                    non-streamed calls report the same; a completion assembled from streamed
 *                    chunks has none, since chunks carry none. Coverage is growing under RFC 0008, so an
 *                    empty list means nothing was reported, not that every option was sent: see
 *                    [[RequestAdjustment]] for what is reported today.
 * @param finishReason Why the model's turn ended (RFC 0009). [[FinishReason.Unknown]] unless the client sets it: a
 *                     client that read no reason leaves it so, and never reports a `Stop` the provider did not send.
 *                     A completion assembled from streamed chunks carries the last reason a chunk had.
 * @param servedTier The [[ServiceTier]] the provider reports it served the request at, which may differ from the
 *                   [[CompletionOptions.serviceTier]] asked for (#2746). A tier the provider names that the enum does
 *                   not is [[ServiceTier.Other]], as written. `None` when the provider reported none: one without
 *                   tiers, or a reply that named none.
 */
@Stable
final case class Completion private (
  id: String,
  created: Instant,
  model: ModelName,
  message: AssistantMessage,
  usage: Option[TokenUsage],
  estimatedCost: Option[BigDecimal],
  citations: Seq[Citation],
  adjustments: Seq[RequestAdjustment],
  finishReason: FinishReason,
  servedTier: Option[ServiceTier]
) {
  def withId(id: String): Completion                                   = copy(id = id)
  def withCreated(created: Instant): Completion                        = copy(created = created)
  def withModel(model: ModelName): Completion                          = copy(model = model)
  def withMessage(message: AssistantMessage): Completion               = copy(message = message)
  def withUsage(usage: TokenUsage): Completion                         = copy(usage = Some(usage))
  def withUsage(usage: Option[TokenUsage]): Completion                 = copy(usage = usage)
  def withEstimatedCost(estimatedCost: BigDecimal): Completion         = copy(estimatedCost = Some(estimatedCost))
  def withEstimatedCost(estimatedCost: Option[BigDecimal]): Completion = copy(estimatedCost = estimatedCost)
  def withCitations(citations: Seq[Citation]): Completion              = copy(citations = citations)
  def withAdjustments(adjustments: Seq[RequestAdjustment]): Completion = copy(adjustments = adjustments)
  def withFinishReason(finishReason: FinishReason): Completion         = copy(finishReason = finishReason)
  def withServedTier(servedTier: ServiceTier): Completion              = copy(servedTier = Some(servedTier))
  def withServedTier(servedTier: Option[ServiceTier]): Completion      = copy(servedTier = servedTier)

  /** The text of the reply: [[message]]'s content, `""` when the model produced only tool calls. */
  def content: String = message.content

  /** The tool calls the model asked for: [[message]]'s tool calls. */
  def toolCalls: Seq[ToolCall] = message.toolCalls

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
    created: Instant,
    model: ModelName,
    message: AssistantMessage,
    usage: Option[TokenUsage] = None,
    estimatedCost: Option[BigDecimal] = None,
    citations: Seq[Citation] = Seq.empty,
    adjustments: Seq[RequestAdjustment] = Seq.empty,
    finishReason: FinishReason = FinishReason.Unknown,
    servedTier: Option[ServiceTier] = None
  ): Completion =
    new Completion(
      id,
      created,
      model,
      message,
      usage,
      estimatedCost,
      citations,
      adjustments,
      finishReason,
      servedTier
    )

}

/**
 * Token usage statistics for a completion request.
 *
 * @param promptTokens Number of tokens in the whole prompt (input), prompt-cache reads and writes included:
 *                     `cachedTokens` and `cacheCreationTokens` are parts of it, and the uncached input is
 *                     what is left. A provider whose API reports the uncached input alone (Anthropic's
 *                     `input_tokens`) has its client add the cache counts, so `CostEstimator` prices each
 *                     token once.
 * @param completionTokens Number of tokens in the completion (output).
 * @param totalTokens Total tokens (prompt + completion).
 * @param thinkingTokens Optional number of tokens used for thinking/reasoning, billed as output.
 *                       They are never part of `completionTokens`: a provider that counts them inside
 *                       its completion count (OpenAI's `reasoning_tokens`, and servers that copy its
 *                       format) has them subtracted by its client, while Gemini and xAI already count
 *                       them apart. `totalTokens` is kept as the provider reported it.
 *                       [[totalOutputTokens]] is the two added together.
 * @param cachedTokens Optional number of tokens served from the provider's prompt cache (cache read).
 *                     When present, these tokens are billed at the cheaper cache-read rate.
 * @param cacheCreationTokens Optional number of tokens written into the provider's prompt cache.
 *                            When present, these tokens are billed at the cache-creation rate,
 *                            which is typically higher than the normal input rate.
 * @param longCacheCreationTokens Optional number of `cacheCreationTokens` written with the provider's long lifetime
 *                                (Anthropic's 1-hour cache), billed at its own, higher rate. The rest of
 *                                `cacheCreationTokens` were written with the short lifetime.
 */
@Stable
final case class TokenUsage private (
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int,
  thinkingTokens: Option[Int],
  cachedTokens: Option[Int],
  cacheCreationTokens: Option[Int],
  longCacheCreationTokens: Option[Int]
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
  def withLongCacheCreationTokens(longCacheCreationTokens: Int): TokenUsage =
    copy(longCacheCreationTokens = Some(longCacheCreationTokens))
  def withLongCacheCreationTokens(longCacheCreationTokens: Option[Int]): TokenUsage =
    copy(longCacheCreationTokens = longCacheCreationTokens)

  /**
   * Total output tokens: the completion plus any thinking, which `completionTokens` never includes.
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
    cacheCreationTokens: Option[Int] = None,
    longCacheCreationTokens: Option[Int] = None
  ): TokenUsage =
    new TokenUsage(
      promptTokens,
      completionTokens,
      totalTokens,
      thinkingTokens,
      cachedTokens,
      cacheCreationTokens,
      longCacheCreationTokens
    )
}

/**
 * Token usage statistics for an embedding request.
 *
 * @param promptTokens Number of tokens in the input text(s).
 * @param totalTokens Total tokens used (same as promptTokens for embeddings).
 *
 * Build one with [[EmbeddingUsage.apply]] and change it with the `with*` setters; the constructor and `copy` are
 * private, so a new field never breaks callers.
 */
@Stable
final case class EmbeddingUsage private (
  promptTokens: Int,
  totalTokens: Int
) {

  /** Sets the number of input tokens. */
  def withPromptTokens(promptTokens: Int): EmbeddingUsage = copy(promptTokens = promptTokens)

  /** Sets the total tokens used. */
  def withTotalTokens(totalTokens: Int): EmbeddingUsage = copy(totalTokens = totalTokens)
}

object EmbeddingUsage {

  /**
   * Creates an [[EmbeddingUsage]].
   *
   * @param promptTokens number of input tokens
   * @param totalTokens  total tokens used
   */
  def apply(promptTokens: Int, totalTokens: Int): EmbeddingUsage =
    new EmbeddingUsage(promptTokens, totalTokens)
}

/**
 * Represents a streamed chunk of completion data.
 *
 * @param id Unique identifier for the stream.
 * @param content Optional text content delta.
 * @param toolCall Optional tool call information.
 * @param finishReason Why the model's turn ended, on the chunk that reports it (RFC 0009).
 * @param thinkingDelta Optional thinking/reasoning content delta.
 *                      Present when streaming extended thinking content.
 */
@Stable
final case class StreamedChunk private (
  id: String,
  content: Option[String],
  toolCall: Option[ToolCall],
  finishReason: Option[FinishReason],
  thinkingDelta: Option[String]
) {
  def withId(id: String): StreamedChunk                                   = copy(id = id)
  def withContent(content: String): StreamedChunk                         = copy(content = Some(content))
  def withContent(content: Option[String]): StreamedChunk                 = copy(content = content)
  def withToolCall(toolCall: ToolCall): StreamedChunk                     = copy(toolCall = Some(toolCall))
  def withToolCall(toolCall: Option[ToolCall]): StreamedChunk             = copy(toolCall = toolCall)
  def withFinishReason(finishReason: FinishReason): StreamedChunk         = copy(finishReason = Some(finishReason))
  def withFinishReason(finishReason: Option[FinishReason]): StreamedChunk = copy(finishReason = finishReason)
  def withThinkingDelta(thinkingDelta: String): StreamedChunk             = copy(thinkingDelta = Some(thinkingDelta))
  def withThinkingDelta(thinkingDelta: Option[String]): StreamedChunk     = copy(thinkingDelta = thinkingDelta)

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
    finishReason: Option[FinishReason] = None,
    thinkingDelta: Option[String] = None
  ): StreamedChunk =
    new StreamedChunk(id, content, toolCall, finishReason, thinkingDelta)
}
