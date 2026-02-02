package org.llm4s.llmconnect.caching

import scala.concurrent.duration.FiniteDuration
import org.llm4s.types.Result

sealed abstract case class CacheConfig private (
  similarityThreshold: Double,
  ttl: FiniteDuration,
  maxSize: Int
)

object CacheConfig {
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
