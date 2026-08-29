package org.llm4s.rag.transform

import org.llm4s.types.Result

/**
 * Composable pre-retrieval query transformation.
 *
 * QueryTransformers modify user queries before they are embedded and
 * sent to the vector store, improving retrieval quality.
 *
 * Transforms are applied sequentially in the order they are added
 * to RAGConfig. Each transform receives the output of the previous one.
 *
 * @example
 * {{{
 * val rag = RAG.builder()
 *   .withEmbeddings(EmbeddingProvider.OpenAI)
 *   .withQueryTransformer(LLMQueryRewriter(llmClient))
 *   .build()
 *
 * // "tell me about that config thing"
 * // → rewritten to "RAGConfig configuration options and builder pattern"
 * // → then embedded and searched
 * val results = rag.query("tell me about that config thing")
 * }}}
 */
trait QueryTransformer {

  /**
   * Transform a query before embedding and retrieval.
   *
   * @param query The original or previously-transformed query
   * @return The transformed query, or an error
   */
  def transform(query: String): Result[String]

  /**
   * Human-readable name for this transformer (used in logging/tracing).
   */
  def name: String
}

object QueryTransformer {

  /**
   * Apply a chain of transforms sequentially.
   *
   * Each transform receives the output of the previous one.
   * Short-circuits on the first error.
   *
   * @param query The original query
   * @param transforms The transforms to apply in order
   * @return The fully transformed query
   */
  def applyChain(query: String, transforms: Seq[QueryTransformer]): Result[String] =
    transforms.foldLeft[Result[String]](Right(query))((result, transformer) => result.flatMap(transformer.transform))
}
