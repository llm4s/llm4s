package org.llm4s.agent.memory

import org.llm4s.llmconnect.EmbeddingClient
import org.llm4s.llmconnect.config.{ EmbeddingModelConfig, ProviderConfig }
import org.llm4s.llmconnect.model.EmbeddingRequest
import org.llm4s.types.Result

/**
 * Service for generating embeddings for memory content.
 *
 * This trait abstracts over the embedding generation process,
 * allowing different implementations (LLM providers, local models, etc.).
 */
trait EmbeddingService {

  /**
   * Generate an embedding for a single text.
   *
   * @param text The text to embed
   * @return Embedding vector or error
   */
  def embed(text: String): Result[Array[Float]]

  /**
   * Generate embeddings for multiple texts in a batch.
   *
   * @param texts The texts to embed
   * @return Embedding vectors (one per input) or error
   */
  def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]]

  /**
   * Get the dimensionality of the embeddings produced.
   */
  def dimensions: Int
}

/**
 * Embedding service using an LLM provider (OpenAI, VoyageAI, etc.).
 *
 * @param client The embedding client
 * @param modelConfig The model configuration (dimensions, model name)
 */
final class LLMEmbeddingService private (
  client: EmbeddingClient,
  modelConfig: EmbeddingModelConfig
) extends EmbeddingService {

  override def embed(text: String): Result[Array[Float]] =
    embedBatch(Seq(text)).map(_.head)

  override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
    if (texts.isEmpty) {
      Right(Seq.empty)
    } else {
      val request = EmbeddingRequest(texts, modelConfig)
      client.embed(request).map(response => response.embeddings.map(_.map(_.toFloat).toArray))
    }

  override def dimensions: Int = modelConfig.dimensions
}

object LLMEmbeddingService {

  /**
   * Create an embedding service from an existing client.
   */
  def apply(client: EmbeddingClient, modelConfig: EmbeddingModelConfig): LLMEmbeddingService =
    new LLMEmbeddingService(client, modelConfig)

  /**
   * Create an embedding service with explicit dimensions using the embedding provider config.
   */
  def apply(client: EmbeddingClient, providerConfig: ProviderConfig, dimensions: Int): Result[LLMEmbeddingService] =
    Right(
      LLMEmbeddingService(
        client,
        EmbeddingModelConfig(
          providerConfig.model,
          dimensions
        )
      )
    )

}

/**
 * A mock embedding service for testing.
 *
 * Generates deterministic embeddings based on text content hash.
 * Not suitable for production use.
 */
final class MockEmbeddingService(override val dimensions: Int = 1536) extends EmbeddingService {

  override def embed(text: String): Result[Array[Float]] =
    Right(generateMockEmbedding(text))

  override def embedBatch(texts: Seq[String]): Result[Seq[Array[Float]]] =
    Right(texts.map(generateMockEmbedding))

  /**
   * Generate a deterministic mock embedding based on text hash.
   * Similar texts will have similar embeddings (useful for testing).
   */
  private def generateMockEmbedding(text: String): Array[Float] = {
    val normalized = text.toLowerCase.trim
    val hash       = normalized.hashCode
    val rng        = new scala.util.Random(hash)

    // Generate normalized vector
    val raw = Array.fill(dimensions)(rng.nextFloat() * 2 - 1)

    // Normalize to unit length
    val magnitude = math.sqrt(raw.map(x => x * x).sum).toFloat
    if (magnitude > 0) raw.map(_ / magnitude) else raw
  }
}

object MockEmbeddingService {

  /**
   * Default mock service with 1536 dimensions (OpenAI ada-002 compatible).
   */
  val default: MockEmbeddingService = new MockEmbeddingService(1536)

  /**
   * Create a mock service with custom dimensions.
   */
  def apply(dimensions: Int = 1536): MockEmbeddingService =
    new MockEmbeddingService(dimensions)
}

/**
 * Utilities for working with embedding vectors.
 */
object VectorOps {

  /**
   * Calculate cosine similarity between two vectors.
   *
   * @return Similarity score between -1.0 and 1.0 (1.0 = identical)
   *         Returns 0.0 for non-finite vectors (NaN, Inf, -Inf)
   */
  def cosineSimilarity(a: Array[Float], b: Array[Float]): Double = {
    require(a.length == b.length, s"Vector dimensions must match: ${a.length} != ${b.length}")

    if (a.isEmpty) return 0.0

    // Check for non-finite values early
    var i = 0
    while (i < a.length) {
      if (!java.lang.Float.isFinite(a(i)) || !java.lang.Float.isFinite(b(i))) {
        return 0.0
      }
      i += 1
    }

    var dotProduct = 0.0
    var normA      = 0.0
    var normB      = 0.0

    i = 0
    while (i < a.length) {
      dotProduct += a(i) * b(i)
      normA += a(i) * a(i)
      normB += b(i) * b(i)
      i += 1
    }

    val denominator = math.sqrt(normA) * math.sqrt(normB)
    val result      = if (denominator == 0.0) 0.0 else dotProduct / denominator

    // Guard against NaN in result
    if (java.lang.Double.isFinite(result)) result else 0.0
  }

  /**
   * Calculate Euclidean distance between two vectors.
   *
   * @return Distance (0.0 = identical)
   */
  def euclideanDistance(a: Array[Float], b: Array[Float]): Double = {
    require(a.length == b.length, s"Vector dimensions must match: ${a.length} != ${b.length}")

    if (a.isEmpty) return 0.0

    var sum = 0.0
    var i   = 0
    while (i < a.length) {
      val diff = a(i) - b(i)
      sum += diff * diff
      i += 1
    }

    math.sqrt(sum)
  }

  /**
   * Normalize a vector to unit length.
   */
  def normalize(v: Array[Float]): Array[Float] = {
    val magnitude = math.sqrt(v.map(x => x.toDouble * x).sum).toFloat
    if (magnitude > 0) v.map(_ / magnitude) else v
  }

  /**
   * Calculate dot product of two vectors.
   */
  def dotProduct(a: Array[Float], b: Array[Float]): Double = {
    require(a.length == b.length, s"Vector dimensions must match: ${a.length} != ${b.length}")

    var sum = 0.0
    var i   = 0
    while (i < a.length) {
      sum += a(i) * b(i)
      i += 1
    }
    sum
  }

  /**
   * Find the top-K most similar vectors to a query.
   *
   * @param query The query vector
   * @param candidates List of candidate vectors with associated data
   * @param k Number of results to return
   * @return Top-K candidates sorted by similarity (descending)
   */
  def topKBySimilarity[T](
    query: Array[Float],
    candidates: Seq[(Array[Float], T)],
    k: Int
  ): Seq[(T, Double)] =
    candidates
      .map { case (vector, data) =>
        (data, cosineSimilarity(query, vector))
      }
      .sortBy(-_._2)
      .take(k)
}
