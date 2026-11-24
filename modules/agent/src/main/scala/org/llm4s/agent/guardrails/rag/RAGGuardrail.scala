package org.llm4s.agent.guardrails.rag

import org.llm4s.annotation.Experimental
import org.llm4s.agent.AgentRun
import org.llm4s.agent.graph.middleware.{ ModelContext, UsageAccounting }
import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.llmconnect.model.{ AssistantMessage, Message, ToolMessage, UserMessage }
import org.llm4s.types.Result

import scala.annotation.unused

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
@Experimental
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

@Experimental
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

  /**
   * The context of an agent turn whose retrieval is a tool call. The query is the turn's user message, and every
   * result of a tool named in `tools` is one chunk, in the order the results were stored and exactly as the model read
   * it: the tool's whole result, an error it returned included. There are no sources, because a tool result names
   * none in a form this can read; a tool that returns its sources needs its own function from the turn to a context.
   * No such tool call in `turn` gives a context with no chunks.
   *
   * @param turn the turn's messages, as [[RAGGuardrail.withTurnContext]] passes them
   * @param tools the names of the retrieval tools
   */
  def fromToolResults(turn: Seq[Message], tools: Set[String]): RAGContext = {
    val retrievals = turn
      .collect { case a: AssistantMessage => a.toolCalls }
      .flatten
      .collect {
        case call if tools.contains(call.name) => call.id
      }
      .toSet
    new RAGContext(
      query = turn.collectFirst { case u: UserMessage => u.content }.getOrElse(""),
      retrievedChunks = turn.collect { case t: ToolMessage if retrievals.contains(t.toolCallId) => t.content },
      sources = Seq.empty
    )
  }
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
@Experimental
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
   * Validates `output` inside an agent's hook, as `validateWithContext(output, context)` does. A guardrail that calls
   * a model overrides this to call through `hook.accounted`, so the call is counted in the thread's usage and held to
   * the run's token budget; [[RAGGuardrail.withTurnContext]] calls this form.
   */
  def validateWithContext(output: String, context: RAGContext, @unused hook: ModelContext): Result[String] =
    validateWithContext(output, context)

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
@Experimental
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

    override def validateWithContext(output: String, context: RAGContext, hook: ModelContext): Result[String] =
      guardrails.foldLeft[Result[String]](Right(output)) { (result, guardrail) =>
        result.flatMap(guardrail.validateWithContext(_, context, hook))
      }

    val name: String = s"CompositeRAGGuardrail(${guardrails.map(_.name).mkString(", ")})"

    override val description: Option[String] = Some(
      s"Composite RAG guardrail combining: ${guardrails.map(_.name).mkString(", ")}"
    )
  }

  /**
   * `guardrail` as an output guardrail an agent can run: in `GuardrailMiddleware` (or `GuardrailReviewMiddleware`) it
   * validates the turn's final answer with the [[RAGContext]] that `retrieved` builds from the turn's messages.
   *
   * `retrieved` is given the current turn as the thread stores it, from the turn's user message to the final answer:
   * the user message, each assistant message with its tool calls, each tool result, and the answer last. It says where
   * the retrieved chunks are - [[RAGContext.fromToolResults]] reads them from the results of a retrieval tool - because
   * the runtime does not know which tool, if any, retrieves. `None` means the turn has nothing to check, and the answer
   * passes unchecked; a context with no chunks is still checked, so the guardrail's own rule for it applies (a blocking
   * [[GroundingGuardrail]] rejects an answer that retrieved nothing).
   *
   * The judge's model calls go through the hook's `ModelContext`, so they are counted in the thread's usage. A graph
   * whose state has no message history fails the check, which blocks the answer. Outside an agent - `validate(value)`
   * with no hook - there is no turn, so it is `guardrail.validate(value)`, which passes.
   */
  def withTurnContext(guardrail: RAGGuardrail)(retrieved: Seq[Message] => Option[RAGContext]): OutputGuardrail =
    new OutputGuardrail {
      def validate(value: String): Result[String] = guardrail.validate(value)

      override def validateIn(value: String, context: UsageAccounting): Result[String] = context match {
        case hook: ModelContext =>
          AgentRun.turnMessages(hook.state).flatMap { turn =>
            retrieved(turn).fold[Result[String]](Right(value))(guardrail.validateWithContext(value, _, hook))
          }
        // a stage with no turn to read - an admission - has nothing to check the answer against
        case _ => guardrail.validate(value)
      }

      val name: String                         = guardrail.name
      override def description: Option[String] = guardrail.description
    }
}
