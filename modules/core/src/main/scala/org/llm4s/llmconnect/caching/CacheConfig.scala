package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable
import org.llm4s.error.ValidationError

import scala.concurrent.duration.FiniteDuration
import org.llm4s.types.Result

/**
 * Configuration for the [[CachingLLMClient]] semantic cache.
 *
 * The constructor is private, so every value is checked: build one with [[CacheConfig.create]] and change it with
 * the `with*` setters, each of which returns a `Result` because it checks the new value too.
 *
 * {{{
 * CacheConfig.create(similarityThreshold = 0.95, ttl = 1.hour).flatMap(_.withMaxSize(500))
 * }}}
 *
 * @param similarityThreshold Minimum cosine similarity `[0.0, 1.0]` for a cache
 *                            hit. A value of `1.0` requires near-identical queries;
 *                            lower values allow semantically similar but textually
 *                            different queries to share a cached response.
 * @param ttl                 Maximum age of an entry before it is considered
 *                            expired and the cache is bypassed. Must be positive.
 * @param maxSize             Maximum number of entries in the in-memory cache.
 *                            When the limit is reached the least-recently-used entry
 *                            is evicted automatically.
 */
@Stable
final case class CacheConfig private (
  similarityThreshold: Double,
  ttl: FiniteDuration,
  maxSize: Int
) {

  /** This config with `similarityThreshold`, or the error when it is outside `[0.0, 1.0]`. */
  def withSimilarityThreshold(similarityThreshold: Double): Result[CacheConfig] =
    CacheConfig.create(similarityThreshold, ttl, maxSize)

  /** This config with `ttl`, or the error when it is not positive. */
  def withTtl(ttl: FiniteDuration): Result[CacheConfig] =
    CacheConfig.create(similarityThreshold, ttl, maxSize)

  /** This config with `maxSize`, or the error when it is not positive. */
  def withMaxSize(maxSize: Int): Result[CacheConfig] =
    CacheConfig.create(similarityThreshold, ttl, maxSize)
}

object CacheConfig {

  /**
   * Constructs a validated [[CacheConfig]].
   *
   * All constraints are checked together before returning; the resulting
   * `Left` lists every violation separated by `"; "`.
   *
   * @param similarityThreshold Must be in `[0.0, 1.0]` inclusive; `NaN` is rejected.
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
      // Written as a negated range test so that NaN, for which every comparison is false, is rejected.
      if (!(similarityThreshold >= 0.0 && similarityThreshold <= 1.0))
        Some("similarityThreshold must be between 0.0 and 1.0")
      else None,
      if (ttl.length <= 0) Some("ttl must be positive") else None,
      if (maxSize <= 0) Some("maxSize must be positive") else None
    ).flatten

    if (errors.nonEmpty) Left(ValidationError(errors.mkString("; "), "CacheConfig"))
    else Right(new CacheConfig(similarityThreshold, ttl, maxSize))
  }
}
