package org.llm4s.llmconnect.caching

/**
 * @param size Current number of entries in the cache.
 * @param hits Total number of successful cache lookups.
 * @param misses Total number of lookups that required a new embedding.
 * @param totalRequests Combined sum of hits and misses.
 * @param hitRatePercent Percentage of requests served from cache (0.0 to 100.0).
 */
case class CacheStats(
  size: Int,
  hits: Long,
  misses: Long,
  totalRequests: Long,
  hitRatePercent: Double
)

/**
 * Generic trait for embedding storage backends.
 *
 * @tparam Embedding The type of the embedding representation (usually Seq[Double]).
 */
trait EmbeddingCache[Embedding] {

  /** Retrieves an embedding by its unique key. */
  def get(key: String): Option[Embedding]

  /** Stores an embedding associated with a unique key. */
  def put(key: String, embedding: Embedding): Unit

  /** Optional operation to reset the cache state. */
  def clear(): Unit = ()

  /** Returns performance metrics for this cache instance. */
  def stats(): CacheStats
}
