package org.llm4s.llmconnect.caching

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Thread-safe in-memory implementation of EmbeddingCache.
 * Uses ConcurrentHashMap for storage and atomic counters for statistics.
 *
 * @tparam Embedding The embedding type
 */
class InMemoryEmbeddingCache[Embedding] extends EmbeddingCache[Embedding] {

  private val store  = new ConcurrentHashMap[String, Embedding]()
  private val hits   = new AtomicLong(0L)
  private val misses = new AtomicLong(0L)

  /** Retrieves an embedding and updates hit/miss counters. */
  def get(key: String): Option[Embedding] = {
    val embedding = Option(store.get(key))

    if (embedding.isDefined) hits.incrementAndGet()
    else misses.incrementAndGet()

    embedding
  }

  /** Stores or replaces an embedding for the given key. */
  def put(key: String, embedding: Embedding): Unit =
    store.put(key, embedding)

  /** Clears all cached entries and resets statistics. */
  override def clear(): Unit = {
    store.clear()
    hits.set(0L)
    misses.set(0L)
  }

  /** Returns cache statistics. */
  override def stats(): Map[String, Any] = {
    val totalRequests = hits.get() + misses.get()

    val hitRate =
      if (totalRequests > 0)
        (hits.get().toDouble / totalRequests) * 100
      else 0.0

    Map(
      "size"             -> store.size(),
      "hits"             -> hits.get(),
      "misses"           -> misses.get(),
      "total_requests"   -> totalRequests,
      "hit_rate_percent" -> "%.2f".format(hitRate)
    )
  }
}
