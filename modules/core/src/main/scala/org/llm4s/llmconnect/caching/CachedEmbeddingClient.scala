package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingError, EmbeddingRequest, EmbeddingResponse }
import org.llm4s.types.Result

/**
 * A caching decorator for [[EmbeddingClient]] that avoids redundant provider calls.
 *
 * On each [[embed]] call, input texts are checked against the cache first. Only
 * texts that are not cached are forwarded to the base client — in a single batched
 * request. Results are then stored in the cache and merged with cached hits before
 * being returned, preserving the original input order.
 *
 * Errors returned by the base client are never cached, so transient failures are
 * retried on the next call.
 *
 * '''Note''': [[EmbeddingClient]] is a concrete class rather than a trait, so this
 * wrapper cannot be used as a drop-in substitute for [[EmbeddingClient]] in APIs
 * that require that type. A follow-up issue should extract an embedding service
 * interface to allow proper decorator substitution.
 *
 * @param baseClient   The underlying client used to generate embeddings on cache misses.
 * @param cache        The storage backend for the embedding vectors.
 * @param keyGenerator Function that maps (text, modelName) to a cache key (defaults to SHA-256).
 */
class CachedEmbeddingClient(
  baseClient: EmbeddingClient,
  cache: EmbeddingCache[Seq[Double]],
  keyGenerator: (String, String) => String = CacheKeyGenerator.sha256
) {

  /**
   * Generates embeddings for the provided request, serving cached vectors where
   * available and forwarding all cache misses to the base client in a single call.
   *
   * @param request The embedding request containing one or more input strings.
   * @return A [[Result]] containing an [[EmbeddingResponse]] with one vector per input,
   *         in the same order as [[EmbeddingRequest.input]].
   */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    val modelName = request.model.name

    // Pair each input with its cache key and cached value (if any).
    val keysAndHits: Seq[(String, Option[Seq[Double]])] =
      request.input.map { text =>
        val key = keyGenerator(text, modelName)
        (key, cache.get(key))
      }

    // Collect miss positions and their texts so we can issue one batch call.
    val missesWithIndex: Seq[(Int, String)] =
      keysAndHits.zipWithIndex.collect { case ((_, None), idx) => (idx, request.input(idx)) }

    if (missesWithIndex.isEmpty) {
      // All texts were cache hits — no API call needed.
      Right(EmbeddingResponse(keysAndHits.flatMap(_._2)))
    } else {
      val missTexts = missesWithIndex.map(_._2)
      baseClient.embed(request.copy(input = missTexts)).flatMap { response =>
        if (response.embeddings.size != missTexts.size) {
          Left(
            EmbeddingError(
              code = None,
              message = s"Base client returned ${response.embeddings.size} embeddings for ${missTexts.size} inputs",
              provider = "cached-embedding-client"
            )
          )
        } else {
          // Cache each freshly generated vector.
          missesWithIndex.zip(response.embeddings).foreach { case ((idx, _), vector) =>
            cache.put(keysAndHits(idx)._1, vector)
          }

          // Build an index → vector map for O(1) lookup when assembling the result.
          val freshByIndex: Map[Int, Seq[Double]] =
            missesWithIndex.map(_._1).zip(response.embeddings).toMap

          // Assemble final vectors in the original input order.
          val allVectors: Seq[Seq[Double]] = keysAndHits.zipWithIndex.map {
            case ((_, Some(cached)), _) => cached
            case ((_, None), idx)       => freshByIndex(idx)
          }
          Right(EmbeddingResponse(allVectors))
        }
      }
    }
  }

  /** Returns cache hit/miss statistics for this client. */
  def cacheStats: CacheStats = cache.stats()

  /** Clears all cached vectors and resets statistics. */
  def clearCache(): Unit = cache.clear()
}
