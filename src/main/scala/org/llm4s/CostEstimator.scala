package org.llm4s

import java.io.FileNotFoundException
import scala.util.Try
import scala.io.Source
import play.api.libs.json._

object CostEstimator {

  // Load the litellm pricing data from a JSON resource file
  private val pricingData: JsValue = {
    val resourceUrl = getClass.getResource("/model_prices_and_context_window.json")
    if (resourceUrl == null) {
      throw new FileNotFoundException("Resource 'model_prices_and_context_window.json' not found in the classpath.")
    }

    println(s"DEBUG: JSON file found at: $resourceUrl")  // Debugging output

    val jsonString = Source.fromURL(resourceUrl).mkString
    println(s"DEBUG: JSON Content: $jsonString")  // Debugging output

    Json.parse(jsonString)
  }

  // Method to get pricing for a specific model and provider
  private def getPricing(model: String, provider: String): Option[(Double, Double)] = {
    for {
      inputCost <- (pricingData \ provider \ model \ "input_cost_per_token").asOpt[Double]
      outputCost <- (pricingData \ provider \ model \ "output_cost_per_token").asOpt[Double]
    } yield (inputCost, outputCost)
  }

  // Method to calculate cost based on model, provider, and token counts
  def estimateCost(model: String, provider: String, inputTokens: Int, outputTokens: Int): Option[Double] = {
    getPricing(model, provider).map { case (inputCost, outputCost) =>
      (inputTokens * inputCost) + (outputTokens * outputCost)
    }
  }
}
