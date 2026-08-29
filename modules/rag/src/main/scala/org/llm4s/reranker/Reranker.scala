package org.llm4s.reranker

import org.llm4s.types.Result

/**
 * Result of reranking a single document.
 *
 * @param index Original index in the input documents
 * @param score Relevance score from reranker (higher is better, typically 0-1)
 * @param document The original document content
 */
final case class RerankResult(
  index: Int,
  score: Double,
  document: String
)

/**
 * Request to rerank documents against a query.
 *
 * @param query The query to rank documents against
 * @param documents The documents to rerank
 * @param topK Maximum results to return (None = all)
 * @param returnDocuments Whether to return document content in results
 */
final case class RerankRequest(
  query: String,
  documents: Seq[String],
  topK: Option[Int] = None,
  returnDocuments: Boolean = true
)

/**
 * Response from a reranking operation.
 *
 * @param results Reranked results sorted by score (descending)
 * @param metadata Provider-specific metadata (model, latency, etc.)
 */
final case class RerankResponse(
  results: Seq[RerankResult],
  metadata: Map[String, String] = Map.empty
)

/**
 * Error from a reranking operation.
 *
 * @param code Error code (HTTP status or provider-specific)
 * @param message Human-readable error message
 * @param provider Provider name (cohere, llm, etc.)
 */
final case class RerankError(
  override val code: Option[String],
  override val message: String,
  provider: String
) extends org.llm4s.error.LLMError {
  override val context: Map[String, String]  = Map("provider" -> provider)
  override val correlationId: Option[String] = None
}

/**
 * Configuration for reranker providers.
 *
 * @param baseUrl API endpoint
 * @param apiKey API key for authentication
 * @param model Model name (e.g., "rerank-english-v3.0")
 */
final case class RerankProviderConfig(
  baseUrl: String,
  apiKey: String,
  model: String
)

/**
 * Cross-encoder reranker interface.
 *
 * Rerankers improve search quality by re-scoring candidate documents
 * using a cross-encoder model that sees both query and document together.
 * This produces more accurate relevance scores than bi-encoder (embedding)
 * similarity alone.
 *
 * Usage:
 * {{{
 * val reranker = RerankerFactory.cohere(config)
 * val request = RerankRequest(
 *   query = "What is Scala?",
 *   documents = Seq("Scala is a programming language", "Python is popular"),
 *   topK = Some(5)
 * )
 * val response = reranker.rerank(request)
 * response.foreach { r =>
 *   r.results.foreach(rr => println(s"Score $${rr.score}: $${rr.document.take(50)}..."))
 * }
 * }}}
 */
trait Reranker {

  /**
   * Rerank documents against a query.
   *
   * @param request The rerank request containing query and documents
   * @return Reranked results or error
   */
  def rerank(request: RerankRequest): Result[RerankResponse]
}
