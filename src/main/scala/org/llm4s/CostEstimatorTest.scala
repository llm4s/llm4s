package org.llm4s

object TestCostEstimator {
  def main(args: Array[String]): Unit = {
    
    val testCases = Seq(
      ("gpt-4", "openai", 500, 300),
      ("gpt-4", "azure", 1000, 500),
      ("gpt-3.5", "openai", 750, 250),
      ("invalid-model", "invalid-provider", 500, 300) // Edge case
    )

    for ((model, provider, inputTokens, outputTokens) <- testCases) {
      val connection = new LLMConnection(model, provider)
      val cost = connection.computeCost(inputTokens, outputTokens)
      cost match {
        case Some(amount) => println(s"✅ Cost for $model ($provider): $$${amount}")
        case None => println(s"❌ Failed to estimate cost for $model ($provider). Check model and provider.")
      }
    }
  }
}
