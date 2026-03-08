package org.llm4s.llmconnect.caching

import scala.concurrent.duration.FiniteDuration
import org.llm4s.types.Result

/**
 * Configuration for the [[CachingLLMClient]] semantic cache.
 *
 * Uses the sealed-abstract-case-class pattern to prevent direct construction
 * and disable the generated `copy` method; always construct via [[CacheConfig.create]],
 * which validates all fields and returns a typed error on invalid input.
 *
 * @param similarityThreshold Minimum cosine similarity `[0.0, 1.0]` for a cache
 *                            hit. A value of `1.0` requires near-identical queries;
 *                            lower values allow semantically similar but textually
 *                            different queries to share a cached response.
 * @param ttl                 Maximum age of a [[CacheEntry]] before it is considered
 *                            expired and the cache is bypassed. Must be positive.
 * @param maxSize             Maximum number of entries in the in-memory cache.
 *                            When the limit is reached the least-recently-used entry
 *                            is evicted automatically.
 */
sealed abstract case class CacheConfig private (
  similarityThreshold: Double,
  ttl: FiniteDuration,
  maxSize: Int
)

object CacheConfig {

  /**
   * Constructs a validated [[CacheConfig]].
   *
   * All constraints are checked together before returning; the resulting
   * `Left` lists every violation separated by `"; "`.
   *
   * @param similarityThreshold Must be in `[0.0, 1.0]` inclusive.
   * @param ttl                 Duration must be strictly positive.
   * @param maxSize             Must be strictly positive; defaults to 1 000.
   * @return `Right(config)` when all constraints pass; `Left(ValidationError)`
   *         with all violations when any constraint fails.
   */
  def create(
    similarityThreshold: Double,
    ttl: FiniteDuration,
    maxSize: Int = 1000
  ): Result[CacheConfig] = {
    val errors = List(
      if (similarityThreshold < 0.0 || similarityThreshold > 1.0)
        Some("similarityThreshold must be between 0.0 and 1.0")
      else None,
      if (ttl.length <= 0) Some("ttl must be positive") else None,
      if (maxSize <= 0) Some("maxSize must be positive") else None
    ).flatten

    if (errors.nonEmpty) Left(org.llm4s.error.ValidationError(errors.mkString("; "), "CacheConfig"))
    else Right(new CacheConfig(similarityThreshold, ttl, maxSize) {})
  }
}
