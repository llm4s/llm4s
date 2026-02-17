package org.llm4s.llmconnect.caching

/**
 * Decorator for EmbeddingClient that adds transparent caching.
 *
 * Uses a deterministic cache key (text + model) and stores only successful
 * embedding results.
 *
 * @param baseClient The underlying EmbeddingClient
 * @param cache The cache implementation
 * @tparam Request The request type expected by the base client
 * @tparam Response The response type returned by the base client
 * @tparam Embedding The embedding type
 */
class CachedEmbeddingClient[Request, Response, Embedding](
  val baseClient: EmbeddingClient[Request, Response],
  val cache: EmbeddingCache[Embedding],
  val keyGenerator: (String, String) => String = CacheKeyGenerator.sha256,
  val embeddingExtractor: Response => Option[Embedding]
) {

  /** Embeds text with caching support. */
  def embed(
    text: String,
    model: String,
    request: Option[Request] = None
  ): Either[EmbeddingError, Embedding] = {

    val cacheKey = keyGenerator(text, model)

    cache.get(cacheKey) match {
      case Some(cachedEmbedding) =>
        Right(cachedEmbedding)

      case None =>
        // Use baseClient and request here to resolve "never used" error
        baseClient.embed(text, model, request).flatMap { response =>
          embeddingExtractor(response) match {
            case Some(embedding) =>
              cache.put(cacheKey, embedding)
              Right(embedding)

            case None =>
              Left(EmbeddingError("Failed to extract embedding from response"))
          }
        }
    }
  }

  /**
   * Embeds multiple texts with caching support.
   * Total monadic transformation for explicit error propagation.
   */
  def embedBatch(
    texts: Seq[String],
    model: String,
    request: Option[Request] = None
  ): Either[EmbeddingError, Seq[Embedding]] =
    texts
      .foldLeft(Right(Vector.empty[Embedding]): Either[EmbeddingError, Vector[Embedding]]) { (accEither, text) =>
        for {
          acc <- accEither
          // Calling the local embed method which uses baseClient and request
          embedding <- embed(text, model, request)
        } yield acc :+ embedding
      }
      .map(_.toSeq)

  def getCacheStats(): Map[String, Any] = cache.stats()
  def clearCache(): Unit                = cache.clear()
}

// Re-adding these to resolve "not found: type" errors
trait EmbeddingClient[Request, Response] {
  def embed(
    text: String,
    model: String,
    request: Option[Request] = None
  ): Either[EmbeddingError, Response]
}

case class EmbeddingError(
  message: String,
  cause: Option[Throwable] = None
) extends Exception(message)
