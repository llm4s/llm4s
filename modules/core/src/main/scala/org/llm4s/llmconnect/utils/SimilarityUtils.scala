package org.llm4s.llmconnect.utils

object SimilarityUtils {
  def cosineSimilarity(vec1: Seq[Double], vec2: Seq[Double]): Double = {
    val dot   = vec1.zip(vec2).map { case (a, b) => a * b }.sum
    val normA = math.sqrt(vec1.map(x => x * x).sum)
    val normB = math.sqrt(vec2.map(x => x * x).sum)
    dot / (normA * normB)
  }
}
