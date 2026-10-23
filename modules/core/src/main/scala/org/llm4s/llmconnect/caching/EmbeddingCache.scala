package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable

/**
 * @param size Current number of entries in the cache.
 * @param hits Total number of successful cache lookups.
 * @param misses Total number of lookups that required a new embedding.
 * @param totalRequests Combined sum of hits and misses.
 * @param hitRatePercent Percentage of requests served from cache (0.0 to 100.0).
 */
@Stable
final case class CacheStats private (
  size: Int,
  hits: Long,
  misses: Long,
  totalRequests: Long,
  hitRatePercent: Double
) {
  def withSize(size: Int): CacheStats                        = copy(size = size)
  def withHits(hits: Long): CacheStats                       = copy(hits = hits)
  def withMisses(misses: Long): CacheStats                   = copy(misses = misses)
  def withTotalRequests(totalRequests: Long): CacheStats     = copy(totalRequests = totalRequests)
  def withHitRatePercent(hitRatePercent: Double): CacheStats = copy(hitRatePercent = hitRatePercent)
}

object CacheStats {

  /** Creates a [[CacheStats]]. */
  def apply(size: Int, hits: Long, misses: Long, totalRequests: Long, hitRatePercent: Double): CacheStats =
    new CacheStats(size, hits, misses, totalRequests, hitRatePercent)
}

/**
 * Generic trait for embedding storage backends.
 *
 * @tparam Embedding The type of the embedding representation (usually Seq[Double]).
 */
@Stable
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
