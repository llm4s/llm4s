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
 * @param details extra key/value pairs about the failure, merged into `context`
 *
 * @example
 * {{{
 * val error = ContextError.artifactStoreFailed("store", "tool-output-1", "disk full")
 * error.context("contextType") // "artifactStore"
 * error.context("key")         // "tool-output-1"
 * }}}
 */
@Stable
final case class ContextError private (
  override val message: String,
  contextType: String,
  details: Map[String, String]
) extends LLMError
    with NonRecoverableError {
  override val context: Map[String, String] = Map("contextType" -> contextType) ++ details
}

object ContextError {

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

  /** Unapply extractor for pattern matching: the error's `message`, `contextType` and `details`. */
  def unapply(error: ContextError): Option[(String, String, Map[String, String])] =
    Some((error.message, error.contextType, error.details))
}
