package org.llm4s

import scala.io.Source
import play.api.libs.json._
import java.io.FileNotFoundException

class LLMConnection(val model: String, val provider: String) {

  // URL to fetch the latest pricing data from LiteLLM
  private val litellmUrl = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"

  // Function to fetch live pricing or fallback to local file
  private def fetchPricingData: JsValue = {
  try {
    val jsonString = Source.fromURL(litellmUrl).mkString
    val json = Json.parse(jsonString)

    // Save the JSON to a file for debugging
    val writer = new java.io.PrintWriter("pricing_data.json")
    writer.write(Json.prettyPrint(json))
    writer.close()

    println("✅ JSON saved to pricing_data.json")
    json
  } catch {
    case ex: Exception =>
      println(s"⚠️ Failed to fetch latest pricing data: ${ex.getMessage}, using local JSON file.")
      val resourceStream = getClass.getResourceAsStream("/model_prices_and_context_window.json")
      Json.parse(Source.fromInputStream(resourceStream).mkString)
  }
}




  private val pricingData: JsValue = fetchPricingData

  // Fetch input and output cost per token for the given model & provider
  private def getPricing: Option[(Double, Double)] = {
  println(s"🔍 Looking for model: `$model`, provider: `$provider`")

  val possibleKeys = Seq(model, s"$model-turbo", s"$provider/$model", s"$provider/$model-turbo")
  val modelPath = possibleKeys.collectFirst {
    case key if (pricingData \ key).asOpt[JsObject].isDefined => (pricingData \ key).as[JsObject]
  }

  modelPath match {
    case Some(json) =>
      val inputCost = (json \ "input_cost_per_token").asOpt[Double]
      val outputCost = (json \ "output_cost_per_token").asOpt[Double]

      if (inputCost.isDefined && outputCost.isDefined) {
        println(s"✅ Found pricing: input = ${inputCost.get}, output = ${outputCost.get}")
        Some((inputCost.get, outputCost.get))
      } else {
        println(s"❌ Pricing keys missing for `$model` under `$provider`. Check JSON format.")
        None
      }

    case None =>
      println(s"❌ Model `$model` not found under provider `$provider` in JSON.")
      None
  }
}






  // Compute cost based on token usage
  def computeCost(inputTokens: Int, outputTokens: Int): Option[Double] = {
    getPricing.map { case (inputCost, outputCost) =>
      val totalCost = (inputTokens * inputCost) + (outputTokens * outputCost)
      BigDecimal(totalCost).setScale(4, BigDecimal.RoundingMode.HALF_UP).toDouble
    }
  }
}
