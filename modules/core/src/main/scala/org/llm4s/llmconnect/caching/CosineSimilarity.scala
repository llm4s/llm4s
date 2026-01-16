package org.llm4s.llmconnect.caching

object CosineSimilarity {
  def calculate(v1: Seq[Double], v2: Seq[Double]): Double = {
    if (v1.isEmpty || v2.isEmpty || v1.length != v2.length) {
      0.0
    } else {
      val dotProduct = v1.zip(v2).map { case (a, b) => a * b }.sum
      val norm1 = Math.sqrt(v1.map(x => x * x).sum)
      val norm2 = Math.sqrt(v2.map(x => x * x).sum)
      
      if (norm1 == 0.0 || norm2 == 0.0) 0.0
      else dotProduct / (norm1 * norm2)
    }
  }
}
