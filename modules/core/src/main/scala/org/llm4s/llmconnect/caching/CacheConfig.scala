package org.llm4s.llmconnect.caching

import scala.concurrent.duration.FiniteDuration

case class CacheConfig(
  similarityThreshold: Double,
  ttl: FiniteDuration
)
