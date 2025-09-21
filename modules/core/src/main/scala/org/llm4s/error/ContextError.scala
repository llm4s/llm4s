package org.llm4s.error

/**
 * Context management related errors for token windows and conversation handling
 */
final case class ContextError private (
  override val message: String,
  contextType: String,
  details: Map[String, String] = Map.empty
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("contextType" -> contextType) ++ details
}

object ContextError {
  def tokenBudgetExceeded(currentTokens: Int, budget: Int): ContextError =
    new ContextError(
      s"Token budget exceeded: $currentTokens tokens > $budget budget",
      "tokenBudget",
      Map("currentTokens" -> currentTokens.toString, "budget" -> budget.toString)
    )

  def invalidTrimming(reason: String): ContextError =
    new ContextError(s"Invalid trimming operation: $reason", "trimming")

  def conversationTooLarge(messageCount: Int, minRequired: Int): ContextError =
    new ContextError(
      s"Conversation too large: $messageCount messages, requires at least $minRequired to fit budget",
      "conversationSize",
      Map("messageCount" -> messageCount.toString, "minRequired" -> minRequired.toString)
    )

  def emptyResult(operation: String): ContextError =
    new ContextError(s"Context operation resulted in empty conversation: $operation", "emptyResult")

  def semanticBlockingFailed(reason: String): ContextError =
    new ContextError(s"Failed to group messages into semantic blocks: $reason", "semanticBlocking")

  def summarizationFailed(blockCount: Int, reason: String): ContextError =
    new ContextError(
      s"Failed to summarize $blockCount semantic blocks: $reason",
      "summarization",
      Map("blockCount" -> blockCount.toString)
    )

  def compressionFailed(strategy: String, reason: String): ContextError =
    new ContextError(
      s"$strategy compression failed: $reason",
      "compression",
      Map("strategy" -> strategy)
    )

  def contextPipelineFailed(step: String, reason: String): ContextError =
    new ContextError(
      s"Context management pipeline failed at step '$step': $reason",
      "pipeline",
      Map("failedStep" -> step)
    )

  def toolCompressionFailed(toolCallId: String, reason: String): ContextError =
    new ContextError(
      s"Tool output compression failed for call '$toolCallId': $reason",
      "toolCompression",
      Map("toolCallId" -> toolCallId)
    )

  def externalizationFailed(contentType: String, size: Long, reason: String): ContextError =
    new ContextError(
      s"Content externalization failed for $contentType ($size bytes): $reason",
      "externalization",
      Map("contentType" -> contentType, "size" -> size.toString)
    )

  def artifactStoreFailed(operation: String, key: String, reason: String): ContextError =
    new ContextError(
      s"Artifact store $operation failed for key '$key': $reason",
      "artifactStore",
      Map("operation" -> operation, "key" -> key)
    )

  def schemaCompressionFailed(schema: String, reason: String): ContextError =
    new ContextError(
      s"Schema-aware compression failed for $schema: $reason",
      "schemaCompression",
      Map("schema" -> schema)
    )

  def llmCompressionFailed(modelName: String, reason: String): ContextError =
    new ContextError(
      s"LLM compression failed using model '$modelName': $reason",
      "llmCompression",
      Map("modelName" -> modelName)
    )

  def tokenEstimationFailed(content: String, reason: String): ContextError =
    new ContextError(
      s"Token estimation failed for content (${content.length} chars): $reason",
      "tokenEstimation",
      Map("contentLength" -> content.length.toString)
    )

  /** Unapply extractor for pattern matching */
  def unapply(error: ContextError): Option[(String, String, Map[String, String])] =
    Some((error.message, error.contextType, error.details))
}
