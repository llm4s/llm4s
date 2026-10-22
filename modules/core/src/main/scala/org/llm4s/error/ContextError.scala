package org.llm4s.error

import org.llm4s.annotation.Stable

/**
 * Context management related errors for token windows and conversation handling.
 *
 * This is a [[NonRecoverableError]]. Build one with the smart constructors in the companion object. Its
 * `context` map is `"contextType" -> contextType` plus the `details` entries.
 *
 * @param message human-readable description of the failure
 * @param contextType short tag for the kind of context operation that failed (e.g. `"tokenBudget"`), stored in
 *                    `context` under the key `contextType`
 * @param details extra key/value pairs about the failure, merged into `context`; empty for constructors that
 *                set none
 *
 * @example
 * {{{
 * val error = ContextError.tokenBudgetExceeded(currentTokens = 1200, budget = 1000)
 * error.context("contextType")   // "tokenBudget"
 * error.context("currentTokens") // "1200"
 * }}}
 */
@Stable
final case class ContextError private (
  override val message: String,
  contextType: String,
  details: Map[String, String] = Map.empty
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("contextType" -> contextType) ++ details
}

object ContextError {

  /**
   * The token count of the context is above its budget.
   *
   * @param currentTokens the number of tokens the context uses
   * @param budget the maximum number of tokens allowed
   * @return an error with contextType `"tokenBudget"` and details `currentTokens` and `budget`
   */
  def tokenBudgetExceeded(currentTokens: Int, budget: Int): ContextError =
    new ContextError(
      s"Token budget exceeded: $currentTokens tokens > $budget budget",
      "tokenBudget",
      Map("currentTokens" -> currentTokens.toString, "budget" -> budget.toString)
    )

  /**
   * A trimming operation on the context is invalid.
   *
   * @param reason why the trimming is invalid
   * @return an error with contextType `"trimming"` and no details
   */
  def invalidTrimming(reason: String): ContextError =
    new ContextError(s"Invalid trimming operation: $reason", "trimming")

  /**
   * The conversation has too many messages to fit the budget.
   *
   * @param messageCount the number of messages in the conversation
   * @param minRequired the minimum number of messages that would be required to fit the budget
   * @return an error with contextType `"conversationSize"` and details `messageCount` and `minRequired`
   */
  def conversationTooLarge(messageCount: Int, minRequired: Int): ContextError =
    new ContextError(
      s"Conversation too large: $messageCount messages, requires at least $minRequired to fit budget",
      "conversationSize",
      Map("messageCount" -> messageCount.toString, "minRequired" -> minRequired.toString)
    )

  /**
   * A context operation left the conversation empty.
   *
   * @param operation the operation that produced the empty conversation
   * @return an error with contextType `"emptyResult"` and no details
   */
  def emptyResult(operation: String): ContextError =
    new ContextError(s"Context operation resulted in empty conversation: $operation", "emptyResult")

  /**
   * Grouping messages into semantic blocks failed.
   *
   * @param reason why the grouping failed
   * @return an error with contextType `"semanticBlocking"` and no details
   */
  def semanticBlockingFailed(reason: String): ContextError =
    new ContextError(s"Failed to group messages into semantic blocks: $reason", "semanticBlocking")

  /**
   * Summarizing semantic blocks failed.
   *
   * @param blockCount the number of semantic blocks being summarized
   * @param reason why the summarization failed
   * @return an error with contextType `"summarization"` and detail `blockCount`
   */
  def summarizationFailed(blockCount: Int, reason: String): ContextError =
    new ContextError(
      s"Failed to summarize $blockCount semantic blocks: $reason",
      "summarization",
      Map("blockCount" -> blockCount.toString)
    )

  /**
   * A compression strategy failed.
   *
   * @param strategy the name of the compression strategy
   * @param reason why the compression failed
   * @return an error with contextType `"compression"` and detail `strategy`
   */
  def compressionFailed(strategy: String, reason: String): ContextError =
    new ContextError(
      s"$strategy compression failed: $reason",
      "compression",
      Map("strategy" -> strategy)
    )

  /**
   * A step of the context management pipeline failed.
   *
   * @param step the name of the step that failed
   * @param reason why the step failed
   * @return an error with contextType `"pipeline"` and detail `failedStep`
   */
  def contextPipelineFailed(step: String, reason: String): ContextError =
    new ContextError(
      s"Context management pipeline failed at step '$step': $reason",
      "pipeline",
      Map("failedStep" -> step)
    )

  /**
   * Compressing the output of a tool call failed.
   *
   * @param toolCallId the id of the tool call whose output was being compressed
   * @param reason why the compression failed
   * @return an error with contextType `"toolCompression"` and detail `toolCallId`
   */
  def toolCompressionFailed(toolCallId: String, reason: String): ContextError =
    new ContextError(
      s"Tool output compression failed for call '$toolCallId': $reason",
      "toolCompression",
      Map("toolCallId" -> toolCallId)
    )

  /**
   * Moving content out of the context (externalization) failed.
   *
   * @param contentType the type of the content being externalized
   * @param size the size of the content, in bytes
   * @param reason why the externalization failed
   * @return an error with contextType `"externalization"` and details `contentType` and `size`
   */
  def externalizationFailed(contentType: String, size: Long, reason: String): ContextError =
    new ContextError(
      s"Content externalization failed for $contentType ($size bytes): $reason",
      "externalization",
      Map("contentType" -> contentType, "size" -> size.toString)
    )

  /**
   * An artifact store operation failed. `ToolOutputCompressor` raises it, with operation
   * `"store"`, when it cannot store a tool output it externalizes.
   *
   * @param operation the store operation that failed (e.g. `"store"`)
   * @param key the artifact key the operation was for
   * @param reason why the operation failed
   * @return an error with contextType `"artifactStore"` and details `operation` and `key`
   */
  def artifactStoreFailed(operation: String, key: String, reason: String): ContextError =
    new ContextError(
      s"Artifact store $operation failed for key '$key': $reason",
      "artifactStore",
      Map("operation" -> operation, "key" -> key)
    )

  /**
   * Schema-aware compression failed. `ToolOutputCompressor` raises it, with the schema
   * `"ToolOutputCompressor"`, when the content of a tool output cannot be parsed as JSON.
   *
   * @param schema the schema (or compressor) the compression was based on
   * @param reason why the compression failed
   * @return an error with contextType `"schemaCompression"` and detail `schema`
   */
  def schemaCompressionFailed(schema: String, reason: String): ContextError =
    new ContextError(
      s"Schema-aware compression failed for $schema: $reason",
      "schemaCompression",
      Map("schema" -> schema)
    )

  /**
   * Compression by an LLM failed. `LLMCompressor` raises it, with the name `"digest"`, when
   * the LLM call that compresses the history digest fails.
   *
   * @param modelName the name given to the LLM compression that failed (`LLMCompressor` passes `"digest"`)
   * @param reason why the compression failed
   * @return an error with contextType `"llmCompression"` and detail `modelName`
   */
  def llmCompressionFailed(modelName: String, reason: String): ContextError =
    new ContextError(
      s"LLM compression failed using model '$modelName': $reason",
      "llmCompression",
      Map("modelName" -> modelName)
    )

  /**
   * Estimating the token count of some content failed.
   *
   * @param content the content whose tokens were being estimated; only its length is kept
   * @param reason why the estimation failed
   * @return an error with contextType `"tokenEstimation"` and detail `contentLength` (the length of `content`,
   *         in characters)
   */
  def tokenEstimationFailed(content: String, reason: String): ContextError =
    new ContextError(
      s"Token estimation failed for content (${content.length} chars): $reason",
      "tokenEstimation",
      Map("contentLength" -> content.length.toString)
    )

  /** Unapply extractor for pattern matching: the error's `message`, `contextType` and `details`. */
  def unapply(error: ContextError): Option[(String, String, Map[String, String])] =
    Some((error.message, error.contextType, error.details))
}
