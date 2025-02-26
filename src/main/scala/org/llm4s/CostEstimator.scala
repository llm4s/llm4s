package org.llm4s

object CostEstimator {
  // Define cost per token (example values, update as needed)
  val costPerToken: Map[String, Double] = Map(
    "gpt-4" -> 0.03,  // Example cost per token in USD
    "gpt-3.5" -> 0.002
  )

  // Method to calculate cost based on model and token count
  def estimateCost(model: String, tokenCount: Int): Double = {
    costPerToken.getOrElse(model, 0.0) * tokenCount
  }
}
