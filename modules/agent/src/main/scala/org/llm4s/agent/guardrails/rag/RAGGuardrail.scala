package org.llm4s.agent.guardrails.rag

import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.types.Result

/**
 * Context provided to RAG guardrails for enhanced validation.
 *
 * RAGContext encapsulates all the information a guardrail needs to validate
 * a response in the context of a RAG (Retrieval-Augmented Generation) workflow.
 *
 * @param query The original user query
 * @param retrievedChunks The chunks retrieved from the vector store
 * @param sources Optional source identifiers for each chunk (file paths, URLs, etc.)
 */
final case class RAGContext(
  query: String,
  retrievedChunks: Seq[String],
  sources: Seq[String] = Seq.empty
) {

  /**
   * Get combined context as a single string.
   */
  def combinedContext: String = retrievedChunks.mkString("\n\n")

  /**
   * Get chunks with their source identifiers.
   */
  def chunksWithSources: Seq[(String, Option[String])] =
    retrievedChunks.zipAll(sources.map(Some(_)), "", None)

  /**
   * Check if we have source information for all chunks.
   */
  def hasCompleteSources: Boolean = sources.size >= retrievedChunks.size
}

object RAGContext {

  /**
   * Create context from query and chunks only.
   */
  def apply(query: String, chunks: Seq[String]): RAGContext =
    new RAGContext(query, chunks, Seq.empty)

  /**
   * Create context with source information.
   */
  def withSources(query: String, chunks: Seq[String], sources: Seq[String]): RAGContext =
    new RAGContext(query, chunks, sources)
}

/**
 * Base trait for RAG-specific guardrails that need retrieval context.
 *
 * RAGGuardrail extends OutputGuardrail with additional context-aware validation
 * methods. This allows guardrails to validate responses against the retrieved
 * chunks from a RAG pipeline, enabling:
 *
 * - **Grounding checks**: Verify the response is supported by retrieved context
 * - **Context relevance**: Check if retrieved chunks are relevant to the query
 * - **Source attribution**: Ensure citations are accurate and present
 *
 * RAG guardrails should implement `validateWithContext` for full RAG validation.
 * The standard `validate` method provides a fallback for non-RAG usage.
 *
 * Example usage:
 * {{{
 * // Create a grounding guardrail
 * val grounding = GroundingGuardrail(llmClient, threshold = 0.8)
 *
 * // Validate with RAG context
 * val context = RAGContext(
 *   query = "What is photosynthesis?",
 *   retrievedChunks = Seq("Plants convert sunlight...", "Chlorophyll absorbs...")
 * )
 * grounding.validateWithContext("Photosynthesis converts light to energy", context)
 * }}}
 *
 * @see GroundingGuardrail for factuality validation
 * @see ContextRelevanceGuardrail for chunk relevance checks
 * @see SourceAttributionGuardrail for citation verification
 */
trait RAGGuardrail extends OutputGuardrail {

  /**
   * Validate an output with full RAG context.
   *
   * This is the primary validation method for RAG guardrails. It receives
   * the response along with the original query and retrieved chunks, allowing
   * for context-aware validation.
   *
   * @param output The generated response to validate
   * @param context The RAG context containing query and retrieved chunks
   * @return Right(output) if valid, Left(error) if validation fails
   */
  def validateWithContext(output: String, context: RAGContext): Result[String]

  /**
   * Optional transformation with context.
   *
   * Override to modify the output based on RAG context (e.g., add citations).
   * Default implementation returns output unchanged.
   *
   * @param output The validated output
   * @param context The RAG context
   * @return The transformed output
   */
  def transformWithContext(output: String, context: RAGContext): String = {
    val _ = context // Intentionally unused in default implementation
    output
  }
}

/**
 * Companion object with utilities for RAG guardrails.
 */
object RAGGuardrail {

  /**
   * Create a composite RAG guardrail that runs all guardrails in sequence.
   *
   * All guardrails must pass for the composite to pass. Processing stops
   * at the first failure.
   *
   * @param guardrails The RAG guardrails to compose
   * @return A composite RAG guardrail
   */
  def all(guardrails: Seq[RAGGuardrail]): RAGGuardrail = new RAGGuardrail {
    def validate(value: String): Result[String] =
      guardrails.foldLeft[Result[String]](Right(value))((result, guardrail) => result.flatMap(guardrail.validate))

    def validateWithContext(output: String, context: RAGContext): Result[String] =
      guardrails.foldLeft[Result[String]](Right(output)) { (result, guardrail) =>
        result.flatMap(guardrail.validateWithContext(_, context))
      }

    val name: String = s"CompositeRAGGuardrail(${guardrails.map(_.name).mkString(", ")})"

    override val description: Option[String] = Some(
      s"Composite RAG guardrail combining: ${guardrails.map(_.name).mkString(", ")}"
    )
  }
}
