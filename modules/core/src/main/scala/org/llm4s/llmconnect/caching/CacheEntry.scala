package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.{ Completion, CompletionOptions }
import java.time.Instant

/**
 * An entry in the [[CachingLLMClient]] semantic cache.
 *
 * Stores the embedding vector of the original query alongside the cached
 * [[org.llm4s.llmconnect.model.Completion]], so that later queries can be matched
 * by cosine similarity rather than exact string equality.
 *
 * @param embedding  L2-normalised embedding vector of the query that produced
 *                   this entry. Used for cosine-similarity lookup against new
 *                   queries. Dimensionality matches the configured embedding model.
 * @param response   The [[org.llm4s.llmconnect.model.Completion]] returned by the LLM
 *                   for the original query. This is the value returned to the caller
 *                   on a cache hit.
 * @param timestamp  Wall-clock time when this entry was inserted. Compared against
 *                   [[CacheConfig.ttl]] to determine whether the entry has expired.
 * @param options    The [[org.llm4s.llmconnect.model.CompletionOptions]] used to produce
 *                   `response`. A cache hit requires an exact match on `options`; mismatched
 *                   options (e.g. different temperature or tool set) result in a
 *                   `OptionsMismatch` miss and bypass the cache.
 */
@Stable
final case class CacheEntry private (
  embedding: Seq[Double],
  response: Completion,
  timestamp: Instant,
  options: CompletionOptions
) {
  def withEmbedding(embedding: Seq[Double]): CacheEntry   = copy(embedding = embedding)
  def withResponse(response: Completion): CacheEntry      = copy(response = response)
  def withTimestamp(timestamp: Instant): CacheEntry       = copy(timestamp = timestamp)
  def withOptions(options: CompletionOptions): CacheEntry = copy(options = options)
}

object CacheEntry {

  /** Creates a [[CacheEntry]]. */
  def apply(
    embedding: Seq[Double],
    response: Completion,
    timestamp: Instant,
    options: CompletionOptions
  ): CacheEntry =
    new CacheEntry(embedding, response, timestamp, options)
}
