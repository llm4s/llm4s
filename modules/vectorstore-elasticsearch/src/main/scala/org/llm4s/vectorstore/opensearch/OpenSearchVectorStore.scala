package org.llm4s.vectorstore.opensearch

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.Result
import org.llm4s.vectorstore.elasticsearch.{ SearchBackend, SearchIndexStore }
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord, VectorStore, VectorStoreStats }

import scala.concurrent.duration.FiniteDuration

/**
 * A [[org.llm4s.vectorstore.VectorStore]] over one OpenSearch index with the k-NN plugin, through its REST API and
 * [[org.llm4s.http.Llm4sHttpClient]]: no OpenSearch client library is involved.
 *
 * It shares everything but the vector field and the kNN query with
 * [[org.llm4s.vectorstore.elasticsearch.ElasticsearchVectorStore]] - OpenSearch keeps Elasticsearch 7.10's document,
 * `_bulk`, `_mget`, `_count`, `_delete_by_query` and query DSL APIs - so records, writes, `get`, `list`, `count`,
 * deletes, `stats` and every [[org.llm4s.vectorstore.MetadataFilter]] behave exactly as they do there.
 *
 * What differs:
 *  - the index is created on the first upsert with `index.knn: true` and a `knn_vector` field sized to
 *    [[OpenSearchConfig#dimensions]] (or the first record), indexed as an HNSW graph with the configured
 *    [[OpenSearchSpaceType]] and [[OpenSearchEngine]];
 *  - `search` is a `knn` query. On Lucene and Faiss the metadata filter goes inside it (efficient filtering), so
 *    `topK` results come back when that many match; on NMSLIB, which has no efficient filtering, the filter is applied
 *    to the `k` nearest neighbours afterwards, so a selective filter can return fewer than `topK` - raise
 *    [[OpenSearchConfig#numCandidates]] to compensate;
 *  - each `_score` is mapped onto `[0, 1]` per space type, as [[OpenSearchSpaceType]] describes;
 *  - authentication is basic or a bearer token ([[OpenSearchAuth]]); AWS SigV4 signing is not supported.
 *
 * Every operation returns a `Result`: a transport failure is the client's [[org.llm4s.error.NetworkError]] or
 * [[org.llm4s.error.TimeoutError]], a 401 or 403 an [[org.llm4s.error.AuthenticationError]], a 429 a
 * [[org.llm4s.error.RateLimitError]], any other non-2xx status a [[org.llm4s.error.ServiceError]] carrying the status
 * and the (redacted) body.
 */
final class OpenSearchVectorStore private (
  val config: OpenSearchConfig,
  httpClient: Llm4sHttpClient,
  ownsClient: Boolean
) extends VectorStore {

  private val store = new SearchIndexStore(OpenSearchVectorStore.Backend(config), httpClient, ownsClient)

  override def upsert(record: VectorRecord): Result[Unit]            = store.upsert(record)
  override def upsertBatch(records: Seq[VectorRecord]): Result[Unit] = store.upsertBatch(records)
  override def delete(id: String): Result[Unit]                      = store.delete(id)
  override def deleteBatch(ids: Seq[String]): Result[Unit]           = store.deleteBatch(ids)
  override def deleteByPrefix(prefix: String): Result[Long]          = store.deleteByPrefix(prefix)
  override def deleteByFilter(filter: MetadataFilter): Result[Long]  = store.deleteByFilter(filter)
  override def clear(): Result[Unit]                                 = store.clear()
  override def get(id: String): Result[Option[VectorRecord]]         = store.get(id)
  override def getBatch(ids: Seq[String]): Result[Seq[VectorRecord]] = store.getBatch(ids)
  override def count(filter: Option[MetadataFilter]): Result[Long]   = store.count(filter)
  override def stats(): Result[VectorStoreStats]                     = store.stats()

  override def search(queryVector: Array[Float], topK: Int, filter: Option[MetadataFilter]): Result[Seq[ScoredRecord]] =
    store.search(queryVector, topK, filter)

  override def list(limit: Int, offset: Int, filter: Option[MetadataFilter]): Result[Seq[VectorRecord]] =
    store.list(limit, offset, filter)

  /** Closes the HTTP client when this store created it; a client passed in belongs to the caller. */
  override def close(): Unit = store.close()
}

object OpenSearchVectorStore {

  /** OpenSearch's side of the shared store: a `knn_vector` field, the `knn` query, its score scales. */
  final private case class Backend(config: OpenSearchConfig) extends SearchBackend {
    def product: String                  = "OpenSearch"
    def errorId: String                  = "opensearch"
    def root: String                     = config.root
    def index: String                    = config.index
    def timeout: FiniteDuration          = config.timeout
    def refreshOnWrite: Boolean          = config.refreshOnWrite
    def dimensions: Option[Int]          = config.dimensions
    def authHeaders: Map[String, String] = config.auth.headers
    def secrets: Seq[String]             = config.auth.secrets
    def maxTopK: Int                     = OpenSearchConfig.MaxK
    def dimensionsKey: String            = "dimension"

    def indexDefinition(dimensions: Int): ujson.Obj = OpenSearchQueries.indexDefinition(dimensions, config)

    def searchBody(queryVector: Array[Float], topK: Int, filter: Option[MetadataFilter]): ujson.Obj = {
      val inside = config.engine.filtersInsideKnn
      val default =
        if (inside || filter.isEmpty) topK
        else math.max(100, topK * 10)
      val k = config.numCandidates.getOrElse(default).max(topK).min(OpenSearchConfig.MaxK)
      OpenSearchQueries.knnSearch(queryVector, topK, k, filter, inside)
    }

    def score(raw: Double): Double = config.spaceType.normalise(raw)
  }

  /**
   * A store over `config`'s index, sending through `httpClient`, which stays the caller's to close.
   *
   * No request is made: the index is created on the first upsert.
   *
   * @return the store, or a [[org.llm4s.error.ConfigurationError]] when [[OpenSearchConfig#validate]] refuses the
   *         config
   */
  def apply(config: OpenSearchConfig, httpClient: Llm4sHttpClient): Result[OpenSearchVectorStore] =
    config.validate.map(valid => new OpenSearchVectorStore(valid, httpClient, ownsClient = false))

  /** A store over `config`'s index with a JDK HTTP client of its own, closed by [[OpenSearchVectorStore#close]]. */
  def apply(config: OpenSearchConfig): Result[OpenSearchVectorStore] =
    config.validate.map(valid => new OpenSearchVectorStore(valid, Llm4sHttpClient.create(), ownsClient = true))

  /** A store over `index` of a local cluster with the security plugin disabled (`http://localhost:9200`). */
  def local(index: String = OpenSearchConfig.DefaultIndex): Result[OpenSearchVectorStore] =
    apply(OpenSearchConfig(index = index))
}
