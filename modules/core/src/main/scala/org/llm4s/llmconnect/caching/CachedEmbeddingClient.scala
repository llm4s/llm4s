package org.llm4s.llmconnect.caching

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.model.{ EmbeddingRequest, EmbeddingResponse, EmbeddingError }
import org.llm4s.types.Result

/**
 * A decorator for [[EmbeddingClient]] that provides transparent, deterministic caching.
 *
 * This client intercepts embedding requests, hashes the input text and model name to
 * create a unique cache key, and returns cached vectors if available. On a cache miss,
 * it delegates to the base client and stores the result.
 *
 * @param baseClient The underlying client used to generate embeddings on cache misses.
 * @param cache The storage backend for the embedding vectors.
 * @param keyGenerator Function to create a unique hash (defaults to SHA-256).
 */
class CachedEmbeddingClient(
  baseClient: EmbeddingClient,
  cache: EmbeddingCache[Seq[Double]],
  keyGenerator: (String, String) => String = CacheKeyGenerator.sha256
) {

  /**
   * Generates embeddings for the provided request, utilizing the cache where possible.
   * @param request The embedding request containing one or more strings.
   * @return A Result containing the accumulated EmbeddingResponse.
   */
  def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    val modelName = request.model.name

    // Map over each input text to resolve it from cache or the base client
    val results: Seq[Result[Seq[Double]]] = request.input.map { text =>
      val cacheKey = keyGenerator(text, modelName)

      cache.get(cacheKey) match {
        case Some(vector) =>
          // Cache Hit: Return the stored vector directly
          Right(vector)

        case None =>
          // Cache Miss: Generate embedding via baseClient for this specific text
          val singleRequest = request.copy(input = Seq(text))
          baseClient.embed(singleRequest).flatMap { response =>
            response.embeddings.headOption match {
              case Some(vector) =>
                cache.put(cacheKey, vector)
                Right(vector)
              case None =>
                Left(
                  EmbeddingError(
                    code = None,
                    message = "No embedding returned from base client",
                    provider = "cached-embedding-client"
                  )
                )
            }
          }
      }
    }
    results
      .foldLeft(Right(Vector.empty[Seq[Double]]): Result[Vector[Seq[Double]]]) { (acc, res) =>
        for {
          vectors <- acc
          vector  <- res
        } yield vectors :+ vector
      }
      .map(allVectors => EmbeddingResponse(allVectors.toSeq))
  }
  def getCacheStats(): CacheStats = cache.stats()
  def clearCache(): Unit          = cache.clear()
}
