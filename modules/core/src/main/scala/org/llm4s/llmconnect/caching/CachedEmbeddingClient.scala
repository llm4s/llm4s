package org.llm4s.llmconnect.caching

import org.llm4s.annotation.Stable
import org.llm4s.llmconnect.model.{
  EmbeddingError,
  EmbeddingRequest,
  EmbeddingResponse,
  InputPurpose,
  MultimediaEmbeddingRequest
}
import org.llm4s.llmconnect.provider.EmbeddingProvider
import org.llm4s.types.Result
import org.slf4j.LoggerFactory

/**
 * A caching decorator for an [[org.llm4s.llmconnect.provider.EmbeddingProvider EmbeddingProvider]], which an
 * [[org.llm4s.llmconnect.EmbeddingClient EmbeddingClient]] is too.
 *
 * On each [[embed]] call, input texts are checked against the cache first. Only
 * texts that are not cached are forwarded to the base client — in a single batched
 * request. Results are then stored in the cache and merged with cached hits before
 * being returned, preserving the original input order.
 *
 * Errors returned by the base client are never cached, so transient failures are
 * retried on the next call. A cache that fails is logged and bypassed: a failed lookup is a miss,
 * a failed store leaves the text uncached.
 *
 * It is itself an `EmbeddingProvider`, so it can be passed where one is expected, and wrapped in an
 * `EmbeddingClient` where one of those is: `new EmbeddingClient(cached)`.
 *
 * A response that needed the base client keeps that response's `usage`, `metadata`, `modality` and `dim`, with the
 * cached vectors merged in; `usage` counts only the texts sent. A response served wholly from the cache has no
 * `usage` and its `dim` is the vectors' length.
 *
 * Multimodal requests are not cached: [[embedMultimodal]] goes straight to the base client.
 *
 * @param baseClient   The underlying client used to generate embeddings on cache misses.
 * @param cache        The storage backend for the embedding vectors.
 * @param keyGenerator Function that maps (text, model name, purpose) to a cache key; defaults to
 *                     [[CacheKeyGenerator.embeddingKey]]. The purpose is an argument so that a query and a
 *                     document with the same text never share an entry.
 */
@Stable
class CachedEmbeddingClient(
  baseClient: EmbeddingProvider,
  cache: EmbeddingCache,
  keyGenerator: (String, String, InputPurpose) => String = CacheKeyGenerator.embeddingKey
) extends EmbeddingProvider {

  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Generates embeddings for the provided request, serving cached vectors where
   * available and forwarding all cache misses to the base client in a single call.
   *
   * @param request The embedding request containing one or more input strings.
   * @return A [[org.llm4s.types.Result Result]] containing an [[org.llm4s.llmconnect.model.EmbeddingResponse EmbeddingResponse]] with one vector per input,
   *         in the same order as [[org.llm4s.llmconnect.model.EmbeddingRequest.input EmbeddingRequest.input]].
   */
  override def embed(request: EmbeddingRequest): Result[EmbeddingResponse] = {
    // A query and a document with the same text are different vectors for the models that embed
    // them differently, so the purpose is part of the key.
    // Pair each input with its cache key and cached value (if any).
    val keysAndHits: Seq[(String, Option[Seq[Double]])] =
      request.input.map { text =>
        val key = keyGenerator(text, request.model.name, request.purpose)
        (key, lookup(key))
      }

    // Collect miss positions and their texts so we can issue one batch call.
    val missesWithIndex: Seq[(Int, String)] =
      keysAndHits.zipWithIndex.collect { case ((_, None), idx) => (idx, request.input(idx)) }

    if (missesWithIndex.isEmpty) {
      // All texts were cache hits — no API call needed.
      val vectors = keysAndHits.flatMap(_._2)
      Right(EmbeddingResponse(vectors, dim = vectors.headOption.map(_.size)))
    } else {
      val missTexts = missesWithIndex.map(_._2).distinct
      baseClient.embed(request.withInput(missTexts)).flatMap { response =>
        if (response.embeddings.size != missTexts.size) {
          Left(
            EmbeddingError(
              code = None,
              message = s"Base client returned ${response.embeddings.size} embeddings for ${missTexts.size} inputs",
              provider = "cached-embedding-client"
            )
          )
        } else {
          // Map unique texts to their new embeddings
          val freshByText = missTexts.zip(response.embeddings).toMap
          // Cache each freshly generated vector.
          missesWithIndex.foreach { case (idx, text) =>
            freshByText.get(text).foreach(vector => store(keysAndHits(idx)._1, vector))
          }

          // Assemble final vectors in the original input order.
          val allVectors: Seq[Seq[Double]] = keysAndHits.zipWithIndex.map {
            case ((_, Some(cached)), _) => cached
            case ((_, None), idx)       => freshByText(request.input(idx))
          }
          Right(response.withEmbeddings(allVectors))
        }
      }
    }
  }

  /** Not cached: delegates to the base client. */
  override def embedMultimodal(request: MultimediaEmbeddingRequest): Result[EmbeddingResponse] =
    baseClient.embedMultimodal(request)

  /** The cache's hit/miss statistics, when it keeps them. */
  def cacheStats: Option[CacheStats] = cache.stats()

  /**
   * Calls the backend's `clear()`. With `InMemoryEmbeddingCache` this empties the cache and resets the
   * statistics; a custom `EmbeddingCache` decides for itself.
   */
  def clearCache(): Result[Unit] = cache.clear()

  private def lookup(key: String): Option[Seq[Double]] =
    cache.get(key) match {
      case Right(hit) => hit
      case Left(error) =>
        logger.warn(s"Embedding cache lookup failed, treating it as a miss: ${error.message}")
        None
    }

  private def store(key: String, vector: Seq[Double]): Unit =
    cache.put(key, vector).left.foreach { error =>
      logger.warn(s"Embedding cache store failed, the vector is not cached: ${error.message}")
    }
}
