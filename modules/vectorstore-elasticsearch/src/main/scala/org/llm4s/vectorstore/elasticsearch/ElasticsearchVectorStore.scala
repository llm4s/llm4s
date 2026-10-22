package org.llm4s.vectorstore.elasticsearch

import org.llm4s.http.Llm4sHttpClient
import org.llm4s.types.Result
import org.llm4s.vectorstore.{ MetadataFilter, ScoredRecord, VectorRecord, VectorStore, VectorStoreStats }

import scala.concurrent.duration.FiniteDuration

/**
 * A [[org.llm4s.vectorstore.VectorStore]] over one Elasticsearch index, through its REST API and
 * [[org.llm4s.http.Llm4sHttpClient]]: no Elasticsearch client library is involved.
 *
 * The index is created on the first upsert if it does not exist, with a `dense_vector` field sized to
 * [[ElasticsearchConfig#dimensions]] (or to the first record) and indexed for kNN with the configured
 * [[ElasticsearchSimilarity]]. Writes go through `_bulk`; `search` is an approximate kNN search whose metadata filter
 * is applied inside the kNN (so `topK` results come back when that many match); the other reads are plain queries.
 * Each [[org.llm4s.vectorstore.MetadataFilter]] becomes a query DSL clause: `Equals` a `term`, `In` a `terms`,
 * `HasKey` an `exists`, `Contains` a `wildcard` (`*substring*`), and `And`, `Or` and `Not` a `bool` query.
 *
 * Reads of an index that does not exist yet answer as an empty store, and `clear()` deletes the index, which the next
 * upsert creates again. Every operation returns a `Result`: a transport failure is the client's
 * [[org.llm4s.error.NetworkError]] or [[org.llm4s.error.TimeoutError]], a 401 or 403 an
 * [[org.llm4s.error.AuthenticationError]], a 429 a [[org.llm4s.error.RateLimitError]], any other non-2xx status a
 * [[org.llm4s.error.ServiceError]] carrying the status and the (redacted) body.
 *
 * `list` pages with `from` and `size`, so `offset + limit` may not exceed the index's `max_result_window`
 * (10,000 by default). `Contains` is a leading-wildcard query, refused by a cluster that sets
 * `search.allow_expensive_queries: false`.
 */
final class ElasticsearchVectorStore private (
  val config: ElasticsearchConfig,
  httpClient: Llm4sHttpClient,
  ownsClient: Boolean
) extends VectorStore {

  private val store = new SearchIndexStore(ElasticsearchVectorStore.Backend(config), httpClient, ownsClient)

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

object ElasticsearchVectorStore {

  /** Elasticsearch's side of the shared store: a `dense_vector` field, the top-level `knn` search, ApiKey auth. */
  final private case class Backend(config: ElasticsearchConfig) extends SearchBackend {
    def product: String                  = "Elasticsearch"
    def errorId: String                  = "elasticsearch"
    def root: String                     = config.root
    def index: String                    = config.index
    def timeout: FiniteDuration          = config.timeout
    def refreshOnWrite: Boolean          = config.refreshOnWrite
    def dimensions: Option[Int]          = config.dimensions
    def authHeaders: Map[String, String] = config.auth.headers
    def secrets: Seq[String]             = config.auth.secrets
    def maxTopK: Int                     = ElasticsearchConfig.MaxCandidates
    def dimensionsKey: String            = "dims"
    def indexDefinition(dimensions: Int): ujson.Obj =
      ElasticsearchQueries.indexDefinition(dimensions, config.similarity)

    def searchBody(queryVector: Array[Float], topK: Int, filter: Option[MetadataFilter]): ujson.Obj = {
      val candidates = config.numCandidates
        .getOrElse(math.max(100, topK * 10))
        .max(topK)
        .min(ElasticsearchConfig.MaxCandidates)
      ElasticsearchQueries.knnSearch(queryVector, topK, candidates, filter)
    }

    /** Elasticsearch's `_score` already lies in `[0, 1]` but for `max_inner_product`, which the store clamps. */
    def score(raw: Double): Double = raw
  }

  /**
   * A store over `config`'s index, sending through `httpClient`, which stays the caller's to close.
   *
   * No request is made: the index is created on the first upsert.
   *
   * @return the store, or a [[org.llm4s.error.ConfigurationError]] when [[ElasticsearchConfig#validate]] refuses
   *         the config
   */
  def apply(config: ElasticsearchConfig, httpClient: Llm4sHttpClient): Result[ElasticsearchVectorStore] =
    config.validate.map(valid => new ElasticsearchVectorStore(valid, httpClient, ownsClient = false))

  /** A store over `config`'s index with a JDK HTTP client of its own, closed by [[ElasticsearchVectorStore#close]]. */
  def apply(config: ElasticsearchConfig): Result[ElasticsearchVectorStore] =
    config.validate.map(valid => new ElasticsearchVectorStore(valid, Llm4sHttpClient.create(), ownsClient = true))

  /** A store over `index` of a local cluster with security disabled (`http://localhost:9200`). */
  def local(index: String = ElasticsearchConfig.DefaultIndex): Result[ElasticsearchVectorStore] =
    apply(ElasticsearchConfig(index = index))
}
