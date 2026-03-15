package org.llm4s.samples.agent

import org.llm4s.agent.Agent
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi._
import org.slf4j.LoggerFactory
import upickle.default._

import scala.concurrent.ExecutionContext.Implicits.global

/**
 * Example demonstrating the Agent's runWithStrategy method for parallel tool execution.
 *
 * When an LLM requests multiple independent tool calls (e.g., weather for several cities),
 * using `runWithStrategy` with `ToolExecutionStrategy.Parallel` can significantly reduce
 * latency by executing all tools simultaneously.
 *
 * Requirements:
 * - Set LLM_MODEL environment variable (e.g., "openai/gpt-4o")
 * - Set appropriate API key (e.g., OPENAI_API_KEY)
 *
 * Run with: sbt "samples/runMain org.llm4s.samples.agent.AsyncToolAgentExample"
 */
object AsyncToolAgentExample {
  private val logger = LoggerFactory.getLogger(getClass)

  // Weather result type
  case class WeatherResult(city: String, temperature: Int, condition: String)
  implicit val weatherResultRW: ReadWriter[WeatherResult] = macroRW

  def main(args: Array[String]): Unit = {
    logger.info("=== Agent with Async Tool Execution ===")
    logger.info("")

    val result = for {
      // Load typed provider configuration and build LLM client
      providerCfg <- Llm4sConfig.provider()
      client      <- LLMConnect.getClient(providerCfg)

      // Create weather tool
      weatherTool <- createWeatherTool()
      tools = new ToolRegistry(Seq(weatherTool))

      agent = new Agent(client)

      // Run with parallel tool execution
      // The LLM will likely request weather for all cities at once,
      // and they'll be executed in parallel instead of sequentially
      _ = logger.info("Running agent with Parallel tool execution strategy...")
      _ = logger.info("Query: What's the weather in London, Paris, and Tokyo?")
      _ = logger.info("")

      startTime = System.currentTimeMillis()

      state <- agent.runWithStrategy(
        query = "What's the weather in London, Paris, and Tokyo? Give me a brief summary.",
        tools = tools,
        toolExecutionStrategy = ToolExecutionStrategy.Parallel,
        maxSteps = Some(5)
      )

      duration = System.currentTimeMillis() - startTime

    } yield {
      logger.info("=== Agent Completed ===")
      logger.info("Status: {}", state.status)
      logger.info("Duration: {}ms", duration)
      logger.info("")

      // Extract final response
      state.conversation.messages.reverse
        .find(_.role == org.llm4s.llmconnect.model.MessageRole.Assistant)
        .foreach { msg =>
          logger.info("Response:")
          logger.info(msg.content)
        }

      logger.info("")
      logger.info("Execution logs:")
      state.logs.foreach(log => logger.info("  {}", log))
    }

    result match {
      case Right(_) =>
        logger.info("")
        logger.info("Example completed successfully!")

      case Left(error) =>
        logger.error("Example failed: {}", error.formatted)
        logger.error("")
        logger.error("Make sure you have set:")
        logger.error("  - LLM_MODEL (e.g., openai/gpt-4o)")
        logger.error("  - OPENAI_API_KEY or appropriate provider key")
    }
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
      "Get current weather for a city",
      schema
    ).withHandler { extractor =>
      extractor.getString("city").map { city =>
        // Simulate API latency (300ms per call)
        logger.info("  [Tool] Fetching weather for {}...", city)
        Thread.sleep(300)

        // Mock weather data
        val temps = Map(
          "London" -> 12,
          "Paris"  -> 15,
          "Tokyo"  -> 22
        )

        val conditions = Map(
          "London" -> "Cloudy with light rain",
          "Paris"  -> "Partly sunny",
          "Tokyo"  -> "Clear skies"
        )

        val result = WeatherResult(
          city = city,
          temperature = temps.getOrElse(city, 20),
          condition = conditions.getOrElse(city, "Unknown")
        )

        logger.info("  [Tool] {} weather: {}C, {}", city, result.temperature, result.condition)
        result
      }
    }.buildSafe()
  }
}
