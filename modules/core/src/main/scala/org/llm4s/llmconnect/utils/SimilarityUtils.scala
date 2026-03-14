package org.llm4s.llmconnect.utils

/** Vector similarity utilities for embedding comparison. */
object SimilarityUtils {

  /**
   * Compute the cosine similarity between two vectors.
   *
   * @param vec1 first vector
   * @param vec2 second vector (must have the same length as `vec1`)
   * @return similarity score in the range [-1.0, 1.0], where 1.0 indicates identical direction
   */
  def cosineSimilarity(vec1: Seq[Double], vec2: Seq[Double]): Double = {
    val dot   = vec1.zip(vec2).map { case (a, b) => a * b }.sum
    val normA = math.sqrt(vec1.map(x => x * x).sum)
    val normB = math.sqrt(vec2.map(x => x * x).sum)
    dot / (normA * normB)
  }
}
