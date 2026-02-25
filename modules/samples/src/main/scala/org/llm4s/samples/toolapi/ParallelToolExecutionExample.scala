package org.llm4s.samples.toolapi

import org.llm4s.toolapi._
import org.slf4j.LoggerFactory
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * Example demonstrating parallel tool execution with different strategies.
 *
 * This example shows how to use `ToolExecutionStrategy` to control how multiple
 * tool calls are executed:
 * - Sequential: One at a time (default, safest)
 * - Parallel: All simultaneously (fastest for independent tools)
 * - ParallelWithLimit: Concurrent with rate limiting
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.toolapi.ParallelToolExecutionExample"
 */
object ParallelToolExecutionExample {
  private val logger = LoggerFactory.getLogger(getClass)

  // Result type
  case class WeatherResult(city: String, temperature: Int, condition: String, requestedAt: Long)
  implicit val weatherResultRW: ReadWriter[WeatherResult] = macroRW

  def main(args: Array[String]): Unit = {
    logger.info("=== Parallel Tool Execution Example ===")
    logger.info("")

    val result = for {
      // Create a weather tool that simulates API delay
      weatherTool <- createWeatherTool()
    } yield {
      val registry = new ToolRegistry(Seq(weatherTool))

      // Create multiple requests (simulating LLM requesting weather for multiple cities)
      val cities   = Seq("London", "Paris", "Tokyo", "New York", "Sydney")
      val requests = cities.map(city => ToolCallRequest("get_weather", ujson.Obj("city" -> city)))

      logger.info("Testing with {} weather lookups (each takes ~200ms)", cities.size)
      logger.info("")

      // 1. Sequential execution
      logger.info("--- Strategy: Sequential ---")
      logger.info("Expected time: ~{}ms (5 x 200ms)", cities.size * 200)

      val seqStart    = System.currentTimeMillis()
      val seqFuture   = registry.executeAll(requests, ToolExecutionStrategy.Sequential)
      val seqResults  = Await.result(seqFuture, 30.seconds)
      val seqDuration = System.currentTimeMillis() - seqStart

      logger.info("Sequential completed in {}ms", seqDuration)
      logResults(seqResults)
      logger.info("")

      // 2. Parallel execution
      logger.info("--- Strategy: Parallel ---")
      logger.info("Expected time: ~200ms (all run simultaneously)")

      val parStart    = System.currentTimeMillis()
      val parFuture   = registry.executeAll(requests, ToolExecutionStrategy.Parallel)
      val parResults  = Await.result(parFuture, 30.seconds)
      val parDuration = System.currentTimeMillis() - parStart

      logger.info("Parallel completed in {}ms", parDuration)
      logResults(parResults)
      logger.info("")

      // 3. Rate-limited parallel execution
      logger.info("--- Strategy: ParallelWithLimit(2) ---")
      logger.info("Expected time: ~600ms (3 batches of 2,2,1)")

      val limitStart    = System.currentTimeMillis()
      val limitFuture   = registry.executeAll(requests, ToolExecutionStrategy.ParallelWithLimit(2))
      val limitResults  = Await.result(limitFuture, 30.seconds)
      val limitDuration = System.currentTimeMillis() - limitStart

      logger.info("ParallelWithLimit(2) completed in {}ms", limitDuration)
      logResults(limitResults)
      logger.info("")

      // Summary
      logger.info("=== Performance Summary ===")
      logger.info("Sequential:          {}ms", seqDuration)
      logger.info("Parallel:            {}ms ({}x faster)", parDuration, seqDuration / parDuration)
      logger.info("ParallelWithLimit(2): {}ms ({}x faster than sequential)", limitDuration, seqDuration / limitDuration)
      logger.info("")
      logger.info("Use Parallel for independent tools with no rate limits.")
      logger.info("Use ParallelWithLimit to respect API rate limits while still gaining speed.")
      logger.info("Use Sequential when tools depend on each other or for debugging.")
    }

    result.left.foreach(err => logger.error("Failed to create weather tool: {}", err.formatted))
  }

  /**
   * Creates a weather tool that simulates API latency.
   */
  private def createWeatherTool(): org.llm4s.types.Result[ToolFunction[Map[String, Any], WeatherResult]] = {
    val schema = Schema
      .`object`[Map[String, Any]]("Weather query parameters")
      .withProperty(Schema.property("city", Schema.string("City name to get weather for")))

    ToolBuilder[Map[String, Any], WeatherResult](
      "get_weather",
      "Get current weather for a city (simulates 200ms API call)",
      schema
    ).withHandler { extractor =>
      extractor.getString("city").map { city =>
        // Simulate API latency
        Thread.sleep(200)

        // Mock weather data
        val temps = Map(
          "London"   -> 12,
          "Paris"    -> 15,
          "Tokyo"    -> 22,
          "New York" -> 8,
          "Sydney"   -> 25
        )

        val conditions = Map(
          "London"   -> "Cloudy",
          "Paris"    -> "Sunny",
          "Tokyo"    -> "Clear",
          "New York" -> "Rainy",
          "Sydney"   -> "Sunny"
        )

        WeatherResult(
          city = city,
          temperature = temps.getOrElse(city, 20),
          condition = conditions.getOrElse(city, "Unknown"),
          requestedAt = System.currentTimeMillis()
        )
      }
    }.buildSafe()
  }

  private def logResults(results: Seq[Either[ToolCallError, ujson.Value]]): Unit =
    results.foreach {
      case Right(json) =>
        val weather = read[WeatherResult](json)
        logger.info("  {}: {}C, {}", weather.city, weather.temperature, weather.condition)
      case Left(error) =>
        logger.error("  Error: {}", error.getFormattedMessage)
    }
}
