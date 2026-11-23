package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError
import org.llm4s.types.Result

import java.time.{ Clock, Instant }
import java.util.concurrent.atomic.AtomicLong
import java.util.Collections
import java.util.LinkedHashMap
import scala.concurrent.duration.FiniteDuration

/**
 * Thread-safe in-memory [[EmbeddingCache]] with LRU eviction and an optional TTL. Its operations never fail.
 *
 * Build one with [[InMemoryEmbeddingCache.create]], which checks its arguments.
 *
 * @param maxSize the maximum number of embeddings to store before evicting the least recently used
 * @param ttl     how long an entry stays valid; an expired entry is evicted when it is next looked up. An entry
 *                exactly `ttl` old is still valid.
 * @param clock   the clock the TTL is measured with
 */
@Stable
final class InMemoryEmbeddingCache private (
  maxSize: Int,
  ttl: Option[FiniteDuration],
  clock: Clock
) extends EmbeddingCache {

  private case class Entry(embedding: Seq[Double], storedAt: Instant)
  private val ttlMillis = ttl.map(_.toMillis)
  private val hits      = new AtomicLong(0L)
  private val misses    = new AtomicLong(0L)

  // LinkedHashMap in access order is the LRU; synchronizedMap makes it thread-safe.
  private val store = Collections.synchronizedMap(
    new LinkedHashMap[String, Entry](math.min(maxSize, 1024), 0.75f, true) {
      override def removeEldestEntry(eldest: java.util.Map.Entry[String, Entry]): Boolean =
        size() > maxSize
    }
  )

  /** Retrieves an embedding and updates the hit and miss counters. */
  override def get(key: String): Result[Option[Seq[Double]]] =
    Right(store.synchronized {
      val validEntry = Option(store.get(key)).filter { entry =>
        val isExpired = ttlMillis.exists(limit => (clock.millis() - entry.storedAt.toEpochMilli) > limit)
        if (isExpired) store.remove(key)
        !isExpired
      }

      if (validEntry.isDefined) hits.incrementAndGet()
      else misses.incrementAndGet()

      validEntry.map(_.embedding)
    })

  /** Stores an embedding, evicting the least recently used entry when full. */
  override def put(key: String, embedding: Seq[Double]): Result[Unit] = {
    store.put(key, Entry(embedding, clock.instant()))
    Right(())
  }

  /** Clears all cached entries and resets the statistics. */
  override def clear(): Result[Unit] = {
    store.clear()
    hits.set(0L)
    misses.set(0L)
    Right(())
  }

  /** Always defined: the in-memory cache counts its hits and misses. */
  override def stats(): Option[CacheStats] =
    Some(CacheStats(size = store.size(), hits = hits.get(), misses = misses.get()))
}

object InMemoryEmbeddingCache {

  /**
   * An in-memory cache, or a `ValidationError` naming every invalid argument.
   *
   * @param maxSize must be positive; defaults to 10 000
   * @param ttl     when given, must be positive; defaults to no expiry
   * @param clock   defaults to the system clock in UTC; tests pass a fixed, movable one
   */
  def create(
    maxSize: Int = 10000,
    ttl: Option[FiniteDuration] = None,
    clock: Clock = Clock.systemUTC()
  ): Result[InMemoryEmbeddingCache] = {
    val errors = List(
      Option.when(maxSize <= 0)("maxSize must be positive"),
      Option.when(ttl.exists(_.length <= 0))("ttl must be positive")
    ).flatten
    if (errors.nonEmpty) Left(ValidationError(errors.mkString("; "), "InMemoryEmbeddingCache"))
    else Right(new InMemoryEmbeddingCache(maxSize, ttl, clock))
  }
}
