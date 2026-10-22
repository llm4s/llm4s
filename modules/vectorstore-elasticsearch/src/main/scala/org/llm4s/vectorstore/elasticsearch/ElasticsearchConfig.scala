package org.llm4s.vectorstore.elasticsearch

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result

import scala.concurrent.duration.*

/**
 * What [[ElasticsearchVectorStore]] needs to reach one index of an Elasticsearch cluster.
 *
 * Load it from configuration with [[org.llm4s.config.ElasticsearchVectorStoreConfigLoader]], which reads
 * `llm4s.vectorstore.elasticsearch`, or build one in code:
 *
 * {{{
 * val config = ElasticsearchConfig("https://es.example.com:9200")
 *   .withIndex("docs")
 *   .withAuth(ElasticsearchAuth.ApiKey(encodedKey))
 * }}}
 *
 * The store checks it with [[ElasticsearchConfig#validate]] when it is built.
 *
 * @param baseUrl        the cluster's root URL, optionally with a path prefix (a proxy), without a query. It must be
 *                       `https` when credentials are sent; plain `http` is accepted for a loopback host
 *                       (`localhost`, `127.0.0.0/8`, `::1`) or when there are no credentials to protect.
 * @param index          the index holding the vectors; Elasticsearch's index-name rules apply (lower case, no
 *                       `\ / * ? " < > | , #` or space, not starting with `-`, `_` or `+`)
 * @param auth           the credentials, [[ElasticsearchAuth.NoAuth]] by default
 * @param similarity     the `dense_vector` similarity the index is created with
 * @param dimensions     the vector size the index is created with; when unset, the size of the first record upserted.
 *                       When set, a record of another size is refused before it is sent.
 * @param numCandidates  kNN `num_candidates`, the candidates each shard considers; when unset, `max(100, 10 * topK)`
 *                       capped at 10,000
 * @param refreshOnWrite whether writes wait for a refresh (`refresh=wait_for`), so that a search straight after an
 *                       upsert or delete sees it. Turn it off for bulk ingestion.
 * @param timeout        how long each HTTP request may take
 */
final case class ElasticsearchConfig private (
  baseUrl: String,
  index: String,
  auth: ElasticsearchAuth,
  similarity: ElasticsearchSimilarity,
  dimensions: Option[Int],
  numCandidates: Option[Int],
  refreshOnWrite: Boolean,
  timeout: FiniteDuration
) {

  def withBaseUrl(baseUrl: String): ElasticsearchConfig                        = copy(baseUrl = baseUrl)
  def withIndex(index: String): ElasticsearchConfig                            = copy(index = index)
  def withAuth(auth: ElasticsearchAuth): ElasticsearchConfig                   = copy(auth = auth)
  def withSimilarity(similarity: ElasticsearchSimilarity): ElasticsearchConfig = copy(similarity = similarity)
  def withDimensions(dimensions: Int): ElasticsearchConfig                     = copy(dimensions = Some(dimensions))
  def withDimensions(dimensions: Option[Int]): ElasticsearchConfig             = copy(dimensions = dimensions)
  def withNumCandidates(numCandidates: Int): ElasticsearchConfig         = copy(numCandidates = Some(numCandidates))
  def withNumCandidates(numCandidates: Option[Int]): ElasticsearchConfig = copy(numCandidates = numCandidates)
  def withRefreshOnWrite(refreshOnWrite: Boolean): ElasticsearchConfig   = copy(refreshOnWrite = refreshOnWrite)
  def withTimeout(timeout: FiniteDuration): ElasticsearchConfig          = copy(timeout = timeout)

  /** Checks the config; the first problem is reported, naming the setting. */
  def validate: Result[ElasticsearchConfig] =
    for {
      _ <- SearchConfigChecks.checkBaseUrl("Elasticsearch", baseUrl, auth != ElasticsearchAuth.NoAuth)
      _ <- SearchConfigChecks.checkIndex("Elasticsearch", index)
      _ <- auth match {
        case ElasticsearchAuth.ApiKey(key) if key.trim.isEmpty || key.exists(c => c.isWhitespace || c.isControl) =>
          Left(ConfigurationError("The Elasticsearch apiKey is blank or contains whitespace"))
        case ElasticsearchAuth.Basic(user, _) if user.trim.isEmpty || user.contains(':') =>
          Left(ConfigurationError("The Elasticsearch username is blank or contains ':'"))
        case _ => Right(())
      }
      _ <- SearchConfigChecks.checkRange("dimensions", dimensions, ElasticsearchConfig.MaxDimensions)
      _ <- SearchConfigChecks.checkRange("numCandidates", numCandidates, ElasticsearchConfig.MaxCandidates)
      _ <-
        if (timeout > Duration.Zero) Right(())
        else Left(ConfigurationError("timeout must be positive"))
    } yield this

  /** The base URL without a trailing slash. */
  private[elasticsearch] def root: String = baseUrl.stripSuffix("/")
}

object ElasticsearchConfig {

  /** A local single-node cluster's address. */
  val DefaultBaseUrl: String = "http://localhost:9200"

  /** The index used unless one is configured. */
  val DefaultIndex: String = "llm4s-vectors"

  /** How long each request may take unless configured otherwise. */
  val DefaultTimeout: FiniteDuration = 30.seconds

  /** Elasticsearch's limit on `dense_vector` dimensions (since 8.11). */
  val MaxDimensions: Int = 4096

  /** Elasticsearch's limit on kNN `k` and `num_candidates`. */
  val MaxCandidates: Int = 10000

  /** Creates a config for the cluster at `baseUrl`. Named arguments are the supported way to set the rest. */
  def apply(
    baseUrl: String = DefaultBaseUrl,
    index: String = DefaultIndex,
    auth: ElasticsearchAuth = ElasticsearchAuth.NoAuth,
    similarity: ElasticsearchSimilarity = ElasticsearchSimilarity.Cosine,
    dimensions: Option[Int] = None,
    numCandidates: Option[Int] = None,
    refreshOnWrite: Boolean = true,
    timeout: FiniteDuration = DefaultTimeout
  ): ElasticsearchConfig =
    new ElasticsearchConfig(baseUrl, index, auth, similarity, dimensions, numCandidates, refreshOnWrite, timeout)
}
