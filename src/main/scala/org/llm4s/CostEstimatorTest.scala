package org.llm4s

object CostEstimatorTest {
  def main(args: Array[String]): Unit = {
    val model = "gpt-4"
    val tokenCount = 500  // Example token count
    val cost = CostEstimator.estimateCost(model, tokenCount)
    println(s"Estimated Cost for 500 tokens on gpt-4: $cost")

  }
}
