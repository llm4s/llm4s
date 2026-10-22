package org.llm4s.vectorstore.opensearch

import org.llm4s.error.ConfigurationError
import org.llm4s.types.Result
import org.llm4s.vectorstore.elasticsearch.SearchConfigChecks

import scala.concurrent.duration.*

/**
 * What [[OpenSearchVectorStore]] needs to reach one index of an OpenSearch cluster with the k-NN plugin.
 *
 * Load it from configuration with [[org.llm4s.config.OpenSearchVectorStoreConfigLoader]], which reads
 * `llm4s.vectorstore.opensearch`, or build one in code:
 *
 * {{{
 * val config = OpenSearchConfig("https://search.example.com:9200")
 *   .withIndex("docs")
 *   .withAuth(OpenSearchAuth.Basic("llm4s", password))
 *   .withEngine(OpenSearchEngine.Faiss)
 * }}}
 *
 * The store checks it with [[OpenSearchConfig#validate]] when it is built.
 *
 * @param baseUrl        the cluster's root URL, optionally with a path prefix (a proxy), without a query. It must be
 *                       `https` when credentials are sent; plain `http` is accepted for a loopback host
 *                       (`localhost`, `127.0.0.0/8`, `::1`) or when there are no credentials to protect.
 * @param index          the index holding the vectors; OpenSearch's index-name rules apply (lower case, no
 *                       `\ / * ? " < > | , #` or space, not starting with `-`, `_` or `+`)
 * @param auth           the credentials, [[OpenSearchAuth.NoAuth]] by default
 * @param spaceType      the `knn_vector` space the index is created with
 * @param engine         the k-NN engine the index is created with; it decides whether filters are applied inside
 *                       the kNN search ([[OpenSearchEngine#filtersInsideKnn]])
 * @param dimensions     the vector size the index is created with; when unset, the size of the first record upserted.
 *                       When set, a record of another size is refused before it is sent.
 * @param m              the HNSW graph's `m` (links per node); when unset, the engine's default
 * @param efConstruction the HNSW graph's `ef_construction`; when unset, the engine's default
 * @param numCandidates  the kNN query's `k`, the neighbours each shard returns before the result is cut to `topK`.
 *                       When unset: `topK` with efficient filtering, and `max(100, 10 * topK)` with a post-filter, so
 *                       that enough candidates survive the filter. Never below `topK`, at most 10,000.
 * @param refreshOnWrite whether writes wait for a refresh (`refresh=wait_for`), so that a search straight after an
 *                       upsert or delete sees it. Turn it off for bulk ingestion.
 * @param timeout        how long each HTTP request may take
 */
final case class OpenSearchConfig private (
  baseUrl: String,
  index: String,
  auth: OpenSearchAuth,
  spaceType: OpenSearchSpaceType,
  engine: OpenSearchEngine,
  dimensions: Option[Int],
  m: Option[Int],
  efConstruction: Option[Int],
  numCandidates: Option[Int],
  refreshOnWrite: Boolean,
  timeout: FiniteDuration
) {

  def withBaseUrl(baseUrl: String): OpenSearchConfig                    = copy(baseUrl = baseUrl)
  def withIndex(index: String): OpenSearchConfig                        = copy(index = index)
  def withAuth(auth: OpenSearchAuth): OpenSearchConfig                  = copy(auth = auth)
  def withSpaceType(spaceType: OpenSearchSpaceType): OpenSearchConfig   = copy(spaceType = spaceType)
  def withEngine(engine: OpenSearchEngine): OpenSearchConfig            = copy(engine = engine)
  def withDimensions(dimensions: Int): OpenSearchConfig                 = copy(dimensions = Some(dimensions))
  def withDimensions(dimensions: Option[Int]): OpenSearchConfig         = copy(dimensions = dimensions)
  def withM(m: Int): OpenSearchConfig                                   = copy(m = Some(m))
  def withM(m: Option[Int]): OpenSearchConfig                           = copy(m = m)
  def withEfConstruction(efConstruction: Int): OpenSearchConfig         = copy(efConstruction = Some(efConstruction))
  def withEfConstruction(efConstruction: Option[Int]): OpenSearchConfig = copy(efConstruction = efConstruction)
  def withNumCandidates(numCandidates: Int): OpenSearchConfig           = copy(numCandidates = Some(numCandidates))
  def withNumCandidates(numCandidates: Option[Int]): OpenSearchConfig   = copy(numCandidates = numCandidates)
  def withRefreshOnWrite(refreshOnWrite: Boolean): OpenSearchConfig     = copy(refreshOnWrite = refreshOnWrite)
  def withTimeout(timeout: FiniteDuration): OpenSearchConfig            = copy(timeout = timeout)

  /** Checks the config; the first problem is reported, naming the setting. */
  def validate: Result[OpenSearchConfig] =
    for {
      _ <- SearchConfigChecks.checkBaseUrl("OpenSearch", baseUrl, auth != OpenSearchAuth.NoAuth)
      _ <- SearchConfigChecks.checkIndex("OpenSearch", index)
      _ <- auth match {
        case OpenSearchAuth.BearerToken(token)
            if token.trim.isEmpty || token.exists(c => c.isWhitespace || c.isControl) =>
          Left(ConfigurationError("The OpenSearch token is blank or contains whitespace"))
        case OpenSearchAuth.Basic(user, _) if user.trim.isEmpty || user.contains(':') =>
          Left(ConfigurationError("The OpenSearch username is blank or contains ':'"))
        case _ => Right(())
      }
      _ <- SearchConfigChecks.checkRange("dimensions", dimensions, OpenSearchConfig.MaxDimensions)
      _ <- SearchConfigChecks.checkRange("numCandidates", numCandidates, OpenSearchConfig.MaxK)
      _ <- positive("m", m)
      _ <- positive("efConstruction", efConstruction)
      _ <-
        if (timeout > Duration.Zero) Right(())
        else Left(ConfigurationError("timeout must be positive"))
    } yield this

  private def positive(name: String, value: Option[Int]): Result[Unit] =
    value match {
      case Some(n) if n < 1 => Left(ConfigurationError(s"$name must be positive, got $n"))
      case _                => Right(())
    }

  /** The base URL without a trailing slash. */
  private[opensearch] def root: String = baseUrl.stripSuffix("/")
}

object OpenSearchConfig {

  /** A local single-node cluster's address. */
  val DefaultBaseUrl: String = "http://localhost:9200"

  /** The index used unless one is configured. */
  val DefaultIndex: String = "llm4s-vectors"

  /** How long each request may take unless configured otherwise. */
  val DefaultTimeout: FiniteDuration = 30.seconds

  /** The k-NN plugin's limit on `knn_vector` dimensions; an engine or an older version may allow fewer. */
  val MaxDimensions: Int = 16000

  /** The k-NN plugin's limit on a query's `k`. */
  val MaxK: Int = 10000

  /** Creates a config for the cluster at `baseUrl`. Named arguments are the supported way to set the rest. */
  def apply(
    baseUrl: String = DefaultBaseUrl,
    index: String = DefaultIndex,
    auth: OpenSearchAuth = OpenSearchAuth.NoAuth,
    spaceType: OpenSearchSpaceType = OpenSearchSpaceType.CosineSimil,
    engine: OpenSearchEngine = OpenSearchEngine.Lucene,
    dimensions: Option[Int] = None,
    m: Option[Int] = None,
    efConstruction: Option[Int] = None,
    numCandidates: Option[Int] = None,
    refreshOnWrite: Boolean = true,
    timeout: FiniteDuration = DefaultTimeout
  ): OpenSearchConfig =
    new OpenSearchConfig(
      baseUrl,
      index,
      auth,
      spaceType,
      engine,
      dimensions,
      m,
      efConstruction,
      numCandidates,
      refreshOnWrite,
      timeout
    )
}
