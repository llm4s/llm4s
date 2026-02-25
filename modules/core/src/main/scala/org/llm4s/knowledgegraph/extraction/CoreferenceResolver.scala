package org.llm4s.knowledgegraph.extraction

import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ CompletionOptions, Conversation, SystemMessage, UserMessage }
import org.llm4s.types.Result
import org.llm4s.error.ProcessingError
import org.slf4j.LoggerFactory

/**
 * Resolves coreferences within a document before knowledge graph extraction.
 *
 * Uses an LLM to replace pronouns and indirect references (e.g., "he", "the company",
 * "its founder") with explicit entity names. This pre-processing step reduces duplicate
 * nodes that would otherwise be created when the extractor encounters unresolved references.
 *
 * @param llmClient The LLM client to use for coreference resolution
 */
class CoreferenceResolver(llmClient: LLMClient) {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Resolves coreferences in the given text by replacing pronouns and indirect
   * references with their explicit referents.
   *
   * @param text The text to resolve coreferences in
   * @return The resolved text with pronouns replaced, or the original text if no changes needed
   */
  def resolve(text: String): Result[String] = {
    val conversation = Conversation(
      messages = List(
        SystemMessage(SYSTEM_PROMPT),
        UserMessage(buildUserPrompt(text))
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .map(_.content.trim)
      .left
      .map { error =>
        logger.error(s"Coreference resolution failed: ${error.message}")
        ProcessingError("coreference_resolution", s"Failed to resolve coreferences: ${error.message}")
      }
  }

  private def buildUserPrompt(text: String): String =
    s"""Rewrite the following text, replacing all pronouns and indirect references with the explicit entity names they refer to.

Keep the meaning and structure identical. Only replace references — do not add, remove, or rephrase other content.

If the text contains no pronouns or indirect references, return it unchanged.

Text:
$text"""

  private val SYSTEM_PROMPT: String =
    """You are a coreference resolution assistant. Your task is to replace pronouns and indirect references in text with the explicit entity names they refer to. Return only the rewritten text with no additional commentary."""
}
