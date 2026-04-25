package org.llm4s.rag.transform

import org.llm4s.error.ProcessingError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result

/**
 * Rewrites user queries using an LLM to improve retrieval quality.
 *
 * Takes vague, conversational, or poorly-formed queries and rewrites them
 * into precise search queries optimized for semantic vector search.
 *
 * @example
 * {{{
 * val rewriter = LLMQueryRewriter(llmClient)
 *
 * rewriter.transform("tell me about that config thing")
 * // Right("RAGConfig configuration options builder pattern settings")
 *
 * rewriter.transform("how do I use it with postgres?")
 * // Right("PostgreSQL pgvector integration RAG pipeline configuration")
 * }}}
 *
 * @param llmClient The LLM client to use for rewriting
 * @param systemPrompt Custom system prompt (uses default if not provided)
 */
final class LLMQueryRewriter(
  llmClient: LLMClient,
  systemPrompt: String = LLMQueryRewriter.DefaultSystemPrompt
) extends QueryTransformer {

  override val name: String = "llm-query-rewriter"

  override def transform(query: String): Result[String] = {
    val conversation = Conversation(
      Seq(
        SystemMessage(systemPrompt),
        UserMessage(query)
      )
    )

    llmClient
      .complete(conversation, CompletionOptions(temperature = 0.0))
      .map(_.content.trim)
      .left
      .map { error =>
        ProcessingError(
          "query-rewrite",
          s"Failed to rewrite query: ${error.formatted}"
        )
      }
  }
}

object LLMQueryRewriter {

  val DefaultSystemPrompt: String =
    """You are a query rewriting assistant for a semantic search system.
      |Given a user query, rewrite it to be more specific and suitable for semantic vector search.
      |
      |Rules:
      |- Return ONLY the rewritten query, nothing else
      |- Preserve the original intent
      |- Make it more precise and search-friendly
      |- Expand abbreviations and resolve ambiguity where possible
      |- Do not add information the user did not ask about""".stripMargin

  /**
   * Create a rewriter with default settings.
   */
  def apply(llmClient: LLMClient): LLMQueryRewriter =
    new LLMQueryRewriter(llmClient)

  /**
   * Create a rewriter with a custom system prompt.
   */
  def apply(llmClient: LLMClient, systemPrompt: String): LLMQueryRewriter =
    new LLMQueryRewriter(llmClient, systemPrompt)
}
