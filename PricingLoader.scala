package org.llm4s

import scala.io.Source
import scala.util.{Try, Using}
import play.api.libs.json._

object PricingLoader {
  private val pricingUrl = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json"
  private var cachedPricing: Option[JsValue] = None

  // Load pricing data from URL
  def loadPricing(): Option[JsValue] = {
  if (cachedPricing.isEmpty) {
    println("Fetching pricing data from URL...")
    cachedPricing = Using(Source.fromURL(pricingUrl)) { source =>
      val jsonString = source.mkString
      println("Loaded JSON: " + jsonString.take(1000)) // Print more data
      Json.parse(jsonString)
    }.toOption
  }
  cachedPricing
}



  // Get model cost per token for a given provider
  def getModelCost(model: String, provider: String, tokenType: String): Double = {
  val pricing = loadPricing()
  println(s"Checking price for model: $model, provider: $provider, tokenType: $tokenType")

  // Print available providers
  pricing.foreach { json =>
    println("Available providers: " + json.as[JsObject].keys.mkString(", "))
  }

  // Print available models inside provider
  pricing.foreach { json =>
    (json \ provider).asOpt[JsObject].foreach { providerJson =>
      println(s"Available models under $provider: " + providerJson.keys.mkString(", "))
    }
  }

  val cost = pricing.flatMap { json =>
    val price = (json \ provider \ model \ tokenType).asOpt[Double]
    println(s"Found price: $price")
    price
  }.getOrElse(0.0)

  println(s"Final cost per token: $cost")
  cost
}


}
