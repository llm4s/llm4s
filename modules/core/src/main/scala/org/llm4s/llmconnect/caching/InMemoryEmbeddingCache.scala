package org.llm4s.llmconnect.caching

import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Thread-safe in-memory implementation of EmbeddingCache with LRU eviction.
 * * @param maxSize The maximum number of embeddings to store before evicting the oldest.
 * @tparam Embedding The embedding type (usually Seq[Double]).
 */
class InMemoryEmbeddingCache[Embedding](maxSize: Int = 10000) extends EmbeddingCache[Embedding] {

  private val hits   = new AtomicLong(0L)
  private val misses = new AtomicLong(0L)

  /**
   * Internal store using LinkedHashMap with accessOrder = true.
   * Wrapped in synchronizedMap to ensure thread safety.
   */
  private val store = Collections.synchronizedMap(
    new LinkedHashMap[String, Embedding](maxSize, 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, Embedding]): Boolean =
        size() > maxSize
    }
  )

  /** Retrieves an embedding and updates hit/miss counters. */
  def get(key: String): Option[Embedding] = {
    val embedding = Option(store.get(key))

    if (embedding.isDefined) hits.incrementAndGet()
    else misses.incrementAndGet()

    embedding
  }

  /** Stores an embedding, potentially triggering LRU eviction. */
  def put(key: String, embedding: Embedding): Unit =
    store.put(key, embedding)

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
