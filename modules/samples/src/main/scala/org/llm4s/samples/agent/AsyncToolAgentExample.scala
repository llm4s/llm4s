package org.llm4s.samples.agent

import org.llm4s.agent.{ Agent, AgentContext }
import org.llm4s.config.Llm4sConfig
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.toolapi._
import org.slf4j.LoggerFactory
import upickle.default._

/**
 * Example demonstrating parallel tool execution through `AgentContext.toolExecutionStrategy`.
 *
 * When an LLM requests multiple independent tool calls (e.g., weather for several cities),
 * `ToolExecutionStrategy.Parallel` can significantly reduce latency by executing all tools
 * simultaneously.
 *
 * Requirements: a provider that supports tool calling.
 * Runs against the samples' default provider, the `ollama-local` section of
 * `modules/samples/src/main/resources/application.conf`. For another provider, add a
 * section to `application.local.conf` beside it and select it with `LLM4S_PROVIDER`
 * (docs/getting-started/configuration.md#running-the-samples).
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
      providerCfg     <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given org.llm4s.model.ModelRegistryService = registryService
      client <- LLMConnect.getClient(providerCfg)

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

      state <- agent.run(
        query = "What's the weather in London, Paris, and Tokyo? Give me a brief summary.",
        tools = tools,
        maxSteps = Some(5),
        context = AgentContext(toolExecutionStrategy = ToolExecutionStrategy.Parallel)
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
      logger.info("Tool calls made in parallel:")
      state.messages
        .collect { case msg: org.llm4s.llmconnect.model.AssistantMessage => msg.toolCalls }
        .flatten
        .foreach(call => logger.info("  {} {}", call.name, call.arguments.render()))
    }

    result match {
      case Right(_) =>
        logger.info("")
        logger.info("Example completed successfully!")

      case Left(error) =>
        logger.error("Example failed: {}", error.formatted)
        logger.error("")
        logger.error("Make sure you have configured a named provider section (with its API key)")
        logger.error(
          "and selected it with LLM4S_PROVIDER - see docs/getting-started/configuration.md#running-the-samples"
        )
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
