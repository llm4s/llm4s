package org.llm4s.toolapi.tools

import org.llm4s.toolapi._
import upickle.default._

/**
 * Weather tool implementation
 */
object WeatherTool {
  // Define result type
  case class WeatherResult(
    location: String,
    temperature: Double,
    units: String,
    conditions: String
  )

  // Provide implicit reader/writer
  implicit val weatherResultRW: ReadWriter[WeatherResult] = macroRW

  // Define weather parameter schema
  val weatherParamsSchema: ObjectSchema[Map[String, Any]] = Schema
    .`object`[Map[String, Any]]("Weather request parameters")
    .withProperty(
      Schema.property(
        "location",
        Schema
          .string("City and country e.g. Bogotá, Colombia")
      )
    )
    .withProperty(
      Schema.property(
        "units",
        Schema
          .string("Units the temperature will be returned in.")
          .withEnum(Seq("celsius", "fahrenheit"))
      )
    )

  // Define type-safe handler function
  def weatherHandler(params: SafeParameterExtractor): Either[String, WeatherResult] =
    for {
      location <- params.getString("location")
      units    <- params.getString("units")
    } yield {
      // In a real implementation, this would call an actual weather service
      val temp = if (units == "celsius") 22.5 else 72.5
      WeatherResult(
        location = location,
        temperature = temp,
        units = units,
        conditions = "sunny"
      )
    }

  val tool = ToolBuilder[Map[String, Any], WeatherResult](
    "get_weather",
    "Retrieves current weather for the given location.",
    weatherParamsSchema
  ).withHandler(weatherHandler).build()
}
