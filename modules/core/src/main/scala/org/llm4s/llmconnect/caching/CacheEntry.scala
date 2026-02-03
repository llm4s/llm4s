package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.model.{ Completion, CompletionOptions }
import java.time.Instant

case class CacheEntry(
  embedding: Seq[Double],
  response: Completion,
  timestamp: Instant,
  options: CompletionOptions
)
