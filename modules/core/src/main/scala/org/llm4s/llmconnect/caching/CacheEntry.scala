package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.model.{ Completion, CompletionOptions }
import java.time.Instant

/**
 * An entry in the [[CachingLLMClient]] semantic cache.
 *
 * @param embedding  embedding vector of the query that produced this entry, compared by cosine similarity
 * @param response   the [[org.llm4s.llmconnect.model.Completion]] returned on a hit
 * @param timestamp  when the entry was stored, compared against [[CacheConfig.ttl]]
 * @param options    the options that produced `response`; a hit requires a match on every field that
 *                   changes the answer ([[CompletionCacheKey]])
 */
final private[caching] case class CacheEntry(
  embedding: Seq[Double],
  response: Completion,
  timestamp: Instant,
  options: CompletionOptions
)
