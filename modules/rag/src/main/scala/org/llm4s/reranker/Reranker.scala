package org.llm4s.reranker

import org.llm4s.llmconnect.auth.{ ApiKeyProvider, CachingAccessTokenProvider }
import org.llm4s.security.Secret
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
 * Build one with [[RerankProviderConfig.apply]] and change it with the `with*` setters; the constructor and `copy`
 * are private, so a new field never breaks callers.
 *
 * @param baseUrl API endpoint
 * @param apiKey API key for authentication
 * @param model Model name (e.g., "rerank-english-v3.0")
 * @param apiKeyProvider supplies the key per request in place of `apiKey`, so a rotated key is used without
 *                       rebuilding the reranker (#2478): `llm4s.rerank.cohere.apiKeyFile`, or Cohere's shared
 *                       `llm4s.credentials.cohere.apiKeyFile`, sets
 *                       [[org.llm4s.llmconnect.auth.ApiKeyProvider.file]]. `apiKey` then holds the key read at load.
 */
final case class RerankProviderConfig private (
  baseUrl: String,
  apiKey: Secret,
  model: String,
  apiKeyProvider: Option[ApiKeyProvider]
) {

  /** Sets the API endpoint. */
  def withBaseUrl(baseUrl: String): RerankProviderConfig = copy(baseUrl = baseUrl)

  /** Sets the API key. */
  def withApiKey(apiKey: Secret): RerankProviderConfig = copy(apiKey = apiKey)

  /** Sets the model. */
  def withModel(model: String): RerankProviderConfig = copy(model = model)

  /** Supplies the key per request from `provider` rather than sending `apiKey`. */
  def withApiKeyProvider(provider: ApiKeyProvider): RerankProviderConfig = copy(apiKeyProvider = Some(provider))

  /** Supplies the key per request from `provider`, or sends `apiKey` when it is `None`. */
  def withApiKeyProvider(provider: Option[ApiKeyProvider]): RerankProviderConfig = copy(apiKeyProvider = provider)

  /**
   * The key to send with the next request: [[apiKeyProvider]]'s current key when one is set, otherwise `apiKey`.
   * A key file that has become unreadable or empty is a `Left` naming its path, and a provider that throws is an
   * `AuthenticationError` naming the exception's class, as it is for a chat client.
   */
  def currentApiKey(): Result[Secret] =
    apiKeyProvider.fold[Result[Secret]](Right(apiKey))(keys =>
      CachingAccessTokenProvider.guarded(keys.current()).map(key => Secret(key.asKey))
    )
}

object RerankProviderConfig {

  /** Creates a [[RerankProviderConfig]]. `apiKeyProvider` defaults to none, so `apiKey` is sent. */
  def apply(
    baseUrl: String,
    apiKey: Secret,
    model: String,
    apiKeyProvider: Option[ApiKeyProvider] = None
  ): RerankProviderConfig =
    new RerankProviderConfig(baseUrl, apiKey, model, apiKeyProvider)

  /** The signature before `apiKeyProvider` was added, kept so code compiled against it still links. */
  def apply(baseUrl: String, apiKey: Secret, model: String): RerankProviderConfig =
    new RerankProviderConfig(baseUrl, apiKey, model, None)
}

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
