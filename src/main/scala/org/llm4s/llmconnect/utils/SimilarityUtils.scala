package org.llm4s.llmconnect.utils

object SimilarityUtils {
  def cosineSimilarity(vec1: Seq[Double], vec2: Seq[Double]): Double = {
    require(vec1.nonEmpty && vec2.nonEmpty, "Vectors must be non-empty")
    require(vec1.length == vec2.length, s"Vector length mismatch: ${vec1.length} vs ${vec2.length}")
    val dot   = vec1.zip(vec2).map { case (a, b) => a * b }.sum
    val normA = math.sqrt(vec1.map(x => x * x).sum)
    val normB = math.sqrt(vec2.map(x => x * x).sum)
    if (normA == 0.0 || normB == 0.0) 0.0 else dot / (normA * normB)
  }
}
