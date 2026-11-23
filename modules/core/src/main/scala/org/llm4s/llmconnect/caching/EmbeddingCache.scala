package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable
import org.llm4s.types.Result

/**
 * Statistics of an [[EmbeddingCache]].
 *
 * @param size   current number of entries in the cache
 * @param hits   total number of lookups that found a vector
 * @param misses total number of lookups that did not
 */
@Stable
final case class CacheStats private (
  size: Int,
  hits: Long,
  misses: Long
) {

  /** `hits + misses`. */
  def totalRequests: Long = hits + misses

  /** The share of lookups served from the cache, `0.0` to `100.0`; `0.0` before the first lookup. */
  def hitRatePercent: Double =
    if (totalRequests > 0) hits.toDouble / totalRequests * 100 else 0.0

  def withSize(size: Int): CacheStats      = copy(size = size)
  def withHits(hits: Long): CacheStats     = copy(hits = hits)
  def withMisses(misses: Long): CacheStats = copy(misses = misses)
}

object CacheStats {

  /** Creates a [[CacheStats]]. */
  def apply(size: Int, hits: Long, misses: Long): CacheStats =
    new CacheStats(size, hits, misses)
}

/**
 * Storage for the vectors of a [[CachedEmbeddingClient]].
 *
 * Every operation returns a [[org.llm4s.types.Result Result]], so a backend that can fail (Redis, a database) reports
 * the failure instead of throwing. [[CachedEmbeddingClient]] treats a failed `get` as a miss and a failed `put` as
 * not stored, and logs both: the provider still answers. Expiry and eviction are the backend's own concern, set
 * when it is built ([[InMemoryEmbeddingCache.create]] takes a `maxSize` and a `ttl`).
 */
@Stable
trait EmbeddingCache {

  /** The vector stored under `key`, `None` when there is none (or it has expired), or the backend's error. */
  def get(key: String): Result[Option[Seq[Double]]]

  /** Stores `embedding` under `key`. */
  def put(key: String, embedding: Seq[Double]): Result[Unit]

  /** Removes every entry. */
  def clear(): Result[Unit]

  /** Hit and miss statistics, for a backend that keeps them; `None` by default. */
  def stats(): Option[CacheStats] = None
}
