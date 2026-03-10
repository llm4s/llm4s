package org.llm4s.llmconnect.caching

import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import java.util.LinkedHashMap
import scala.concurrent.duration.FiniteDuration

/**
 * Thread-safe in-memory implementation of EmbeddingCache with LRU eviction.
 * @param maxSize The maximum number of embeddings to store before evicting the oldest.
 * @param ttl     Optional Time-To-Live for cache entries. Expired entries are lazily evicted on access.
 * @tparam Embedding The embedding type (usually Seq[Double]).
 */
class InMemoryEmbeddingCache[Embedding](
  maxSize: Int = 10000,
  ttl: Option[FiniteDuration] = None,
  clock: () => Long = () => System.currentTimeMillis()
) extends EmbeddingCache[Embedding] {

  /** Binary-compatible auxiliary constructor matching the pre-clock 2-param signature. */
  def this(maxSize: Int, ttl: Option[FiniteDuration]) = this(maxSize, ttl, () => System.currentTimeMillis())

  private case class CacheEntry(embedding: Embedding, timestamp: Long)
  private val ttlMillis = ttl.map(_.toMillis)
  private val hits      = new AtomicLong(0L)
  private val misses    = new AtomicLong(0L)

  /**
   * Internal store using LinkedHashMap with accessOrder = true.
   * Wrapped in synchronizedMap to ensure thread safety.
   */
  private val store = Collections.synchronizedMap(
    new LinkedHashMap[String, CacheEntry](maxSize, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, CacheEntry]): Boolean =
        size() > maxSize
    }
  )

  /** Retrieves an embedding and updates hit/miss counters. */
  def get(key: String): Option[Embedding] =
    store.synchronized {
      val entryOpt = Option(store.get(key))

      val validEntry = entryOpt.filter { entry =>
        val isExpired = ttlMillis.exists(limit => (clock() - entry.timestamp) > limit)
        if (isExpired) store.remove(key)
        !isExpired
      }

      if (validEntry.isDefined) hits.incrementAndGet()
      else misses.incrementAndGet()

      validEntry.map(_.embedding)
    }

  /** Stores an embedding, potentially triggering LRU eviction. */
  def put(key: String, embedding: Embedding): Unit =
    store.put(key, CacheEntry(embedding, clock()))

  /** Clears all cached entries and resets statistics. */
  override def clear(): Unit = {
    store.clear()
    hits.set(0L)
    misses.set(0L)
  }

  /** Returns type-safe cache statistics. */
  override def stats(): CacheStats = {
    val h     = hits.get()
    val m     = misses.get()
    val total = h + m

    val hitRate = if (total > 0) (h.toDouble / total) * 100 else 0.0

    CacheStats(
      size = store.size(),
      hits = h,
      misses = m,
      totalRequests = total,
      hitRatePercent = hitRate
    )
  }
}
