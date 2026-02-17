package org.llm4s.llmconnect.caching

/**
 * Trait defining the abstraction for embedding caching.
 *
 * @tparam Embedding The embedding type (typically a vector representation)
 */
trait EmbeddingCache[Embedding] {

  /**
   * Retrieve an embedding from the cache.
   *
   * @param key The cache key (typically a hash of text + model)
   * @return Some(embedding) if found, None otherwise
   */
  def get(key: String): Option[Embedding]

  /**
   * Store an embedding in the cache.
   *
   * @param key The cache key
   * @param embedding The embedding to cache
   */
  def put(key: String, embedding: Embedding): Unit

  /**
   * Optional: Clear all entries from the cache.
   */
  def clear(): Unit = ()

  /**
   * Optional: Get cache statistics (size, hits, misses).
   */
  def stats(): Map[String, Any] = Map.empty
}
