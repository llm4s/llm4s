package org.llm4s.rag.loader.internal

import java.util.{ LinkedHashMap => JLinkedHashMap, Map => JMap }

/**
 * A thread-safe least-recently-used cache holding at most `maxEntries`, for the crawler's process-wide caches
 * (robots.txt per host, compiled URL patterns), which crawls on several threads share.
 */
final private[loader] class BoundedCache[K, V](maxEntries: Int) {
  require(maxEntries > 0, s"maxEntries must be positive: $maxEntries")

  private val entries = new JLinkedHashMap[K, V](16, 0.75f, true) {
    override def removeEldestEntry(eldest: JMap.Entry[K, V]): Boolean = this.size() > maxEntries
  }

  def get(key: K): Option[V] = entries.synchronized(Option(entries.get(key)))

  def put(key: K, value: V): Unit = entries.synchronized { entries.put(key, value); () }

  /** The cached value, or `compute()`'s, cached; `compute` runs outside the lock, so it may run twice for one key. */
  def getOrElseUpdate(key: K, compute: => V): V =
    get(key).getOrElse {
      val value = compute
      put(key, value)
      value
    }

  def clear(): Unit = entries.synchronized(entries.clear())

  def size: Int = entries.synchronized(entries.size())
}
